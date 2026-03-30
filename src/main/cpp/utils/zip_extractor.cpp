#include "zip_extractor.h"

#include <archive.h>
#include <archive_entry.h>

#include <cstdlib>
#include <stdexcept>

namespace fs = std::filesystem;

ZipExtractor::ScopeCleaner::ScopeCleaner(fs::path p) : path(std::move(p)) {}

ZipExtractor::ScopeCleaner::~ScopeCleaner() {
    if (!dismissed) {
        std::error_code ec;
        fs::remove_all(path, ec);
    }
}

ZipExtractor::Result ZipExtractor::extract(const fs::path& archivePath,
                                           const fs::path& targetPath,
                                           const Options& options) {
    Result result;

    try {
        if (!fs::exists(archivePath) || !fs::is_regular_file(archivePath)) {
            result.errorMessage = "archive file does not exist: " + archivePath.string();
            return result;
        }

        const fs::path tempRoot = makeTempDir(getArchiveBaseName(archivePath));
        ScopeCleaner cleaner(tempRoot);

        if (!extractRawTo(archivePath, tempRoot, options, result.errorMessage)) {
            return result;
        }

        fs::path finalOutputPath;

        switch (options.mode) {
        case Mode::None: {
            finalOutputPath = targetPath;
            if (!prepareTarget(finalOutputPath, options.overwrite, result.errorMessage)) {
                return result;
            }
            if (!moveChildren(tempRoot, finalOutputPath, result.errorMessage)) {
                return result;
            }
            break;
        }

        case Mode::StripDuplicateRootOnce: {
            finalOutputPath = targetPath;
            if (!prepareTarget(finalOutputPath, options.overwrite, result.errorMessage)) {
                return result;
            }
            if (!normalizeStripDuplicateRootOnce(tempRoot, finalOutputPath, archivePath, result.errorMessage)) {
                return result;
            }
            break;
        }

        case Mode::CollapseSingleDirChainKeepLeaf: {
            if (!normalizeCollapseSingleDirChainKeepLeaf(tempRoot,
                                                         targetPath,
                                                         archivePath,
                                                         options.overwrite,
                                                         finalOutputPath,
                                                         result.errorMessage)) {
                return result;
            }
            break;
        }

        case Mode::CollapseSingleDirChainDropLeaf: {
            if (!normalizeCollapseSingleDirChainDropLeaf(tempRoot,
                                                         targetPath,
                                                         options.overwrite,
                                                         finalOutputPath,
                                                         result.errorMessage)) {
                return result;
            }
            break;
        }
        }

        result.ok = true;
        result.outputPath = finalOutputPath;
        cleaner.dismissed = true;
        return result;
    } catch (const std::exception& ex) {
        result.errorMessage = ex.what();
        return result;
    }
}

fs::path ZipExtractor::makeTempDir(const std::string& hint) {
    const fs::path base = fs::temp_directory_path();

    for (int i = 0; i < 100; ++i) {
        fs::path candidate = base / ("zip_extractor_" + hint + "_" +
                                     std::to_string(std::rand()) + "_" +
                                     std::to_string(i));
        std::error_code ec;
        if (fs::create_directories(candidate, ec) && !ec) {
            return candidate;
        }
    }

    throw std::runtime_error("failed to create temporary directory");
}

bool ZipExtractor::prepareTarget(const fs::path& targetPath,
                                 const bool overwrite,
                                 std::string& errorMessage) {
    std::error_code ec;

    if (fs::exists(targetPath)) {
        if (!overwrite) {
            errorMessage = "target path already exists: " + targetPath.string();
            return false;
        }

        fs::remove_all(targetPath, ec);
        if (ec) {
            errorMessage = "failed to remove existing target path: " + targetPath.string() +
                           ", " + ec.message();
            return false;
        }
    }

    fs::create_directories(targetPath, ec);
    if (ec) {
        errorMessage = "failed to create target directory: " + targetPath.string() +
                       ", " + ec.message();
        return false;
    }

    return true;
}

bool ZipExtractor::extractRawTo(const fs::path& archivePath,
                                const fs::path& outputDir,
                                const Options& options,
                                std::string& errorMessage) {
    archive* reader = archive_read_new();
    archive* writer = archive_write_disk_new();
    archive_entry* entry = nullptr;

    if (reader == nullptr || writer == nullptr) {
        errorMessage = "failed to allocate libarchive objects";
        if (reader) {
            archive_read_free(reader);
        }
        if (writer) {
            archive_write_free(writer);
        }
        return false;
    }

    archive_read_support_format_all(reader);
    archive_read_support_filter_all(reader);

    int diskFlags = ARCHIVE_EXTRACT_SECURE_NODOTDOT | ARCHIVE_EXTRACT_SECURE_SYMLINKS;

    if (options.preserveTime) {
        diskFlags |= ARCHIVE_EXTRACT_TIME;
    }
    if (options.preservePermissions) {
        diskFlags |= ARCHIVE_EXTRACT_PERM | ARCHIVE_EXTRACT_ACL | ARCHIVE_EXTRACT_FFLAGS;
    }

    archive_write_disk_set_options(writer, diskFlags);
    archive_write_disk_set_standard_lookup(writer);

    if (archive_read_open_filename(reader, archivePath.string().c_str(), 10240) != ARCHIVE_OK) {
        errorMessage = std::string("failed to open archive: ") + archive_error_string(reader);
        archive_write_free(writer);
        archive_read_free(reader);
        return false;
    }

    int status = ARCHIVE_OK;
    while ((status = archive_read_next_header(reader, &entry)) == ARCHIVE_OK) {
        const char* originalPath = archive_entry_pathname(entry);
        if (originalPath == nullptr) {
            errorMessage = "archive entry path is null";
            archive_read_close(reader);
            archive_read_free(reader);
            archive_write_free(writer);
            return false;
        }

        fs::path sanitizedRelativePath = sanitizeRelativePath(originalPath);
        if (sanitizedRelativePath.empty()) {
            archive_read_data_skip(reader);
            continue;
        }

        fs::path fullOutputPath = outputDir / sanitizedRelativePath;
        archive_entry_set_pathname(entry, fullOutputPath.string().c_str());

        status = archive_write_header(writer, entry);
        if (status != ARCHIVE_OK) {
            if (status < ARCHIVE_WARN) {
                errorMessage = std::string("archive_write_header failed: ") + archive_error_string(writer);
                archive_read_close(reader);
                archive_read_free(reader);
                archive_write_free(writer);
                return false;
            }
        } else {
            if (!copyData(reader, writer, errorMessage)) {
                archive_read_close(reader);
                archive_read_free(reader);
                archive_write_free(writer);
                return false;
            }
        }

        status = archive_write_finish_entry(writer);
        if (status < ARCHIVE_WARN) {
            errorMessage = std::string("archive_write_finish_entry failed: ") + archive_error_string(writer);
            archive_read_close(reader);
            archive_read_free(reader);
            archive_write_free(writer);
            return false;
        }
    }

    if (status != ARCHIVE_EOF) {
        errorMessage = std::string("failed while reading archive: ") + archive_error_string(reader);
        archive_read_close(reader);
        archive_read_free(reader);
        archive_write_free(writer);
        return false;
    }

    archive_read_close(reader);
    archive_read_free(reader);
    archive_write_close(writer);
    archive_write_free(writer);

    return true;
}

bool ZipExtractor::copyData(archive* reader,
                            archive* writer,
                            std::string& errorMessage) {
    const void* buffer = nullptr;
    size_t size = 0;
    la_int64_t offset = 0;

    while (true) {
        int status = archive_read_data_block(reader, &buffer, &size, &offset);
        if (status == ARCHIVE_EOF) {
            return true;
        }
        if (status != ARCHIVE_OK) {
            errorMessage = std::string("archive_read_data_block failed: ") + archive_error_string(reader);
            return false;
        }

        status = archive_write_data_block(writer, buffer, size, offset);
        if (status != ARCHIVE_OK) {
            errorMessage = std::string("archive_write_data_block failed: ") + archive_error_string(writer);
            return false;
        }
    }
}

fs::path ZipExtractor::sanitizeRelativePath(const fs::path& rawPath) {
    fs::path sanitized;

    for (const auto& part : rawPath) {
        if (part.empty() || part == "." || part == "..") {
            continue;
        }
        sanitized /= part;
    }

    return sanitized;
}

std::vector<fs::directory_entry>
ZipExtractor::listImmediateChildren(const fs::path& dir) {
    std::vector<fs::directory_entry> entries;
    std::error_code ec;

    if (!fs::exists(dir, ec) || !fs::is_directory(dir, ec)) {
        return entries;
    }

    for (const auto& entry : fs::directory_iterator(dir, ec)) {
        if (ec) {
            break;
        }
        entries.push_back(entry);
    }

    return entries;
}

std::optional<fs::directory_entry>
ZipExtractor::getSingleChildDirectory(const fs::path& dir) {
    auto children = listImmediateChildren(dir);
    if (children.size() != 1) {
        return std::nullopt;
    }

    if (!children[0].is_directory()) {
        return std::nullopt;
    }

    return children[0];
}

std::string ZipExtractor::getArchiveBaseName(const fs::path& archivePath) {
    fs::path name = archivePath.filename();

    while (name.has_extension()) {
        name = name.stem();
    }

    return name.string();
}

bool ZipExtractor::moveChildren(const fs::path& fromDir,
                                const fs::path& toDir,
                                std::string& errorMessage) {
    std::error_code ec;

    for (const auto& entry : fs::directory_iterator(fromDir, ec)) {
        if (ec) {
            errorMessage = "failed to iterate directory: " + fromDir.string() + ", " + ec.message();
            return false;
        }

        fs::path destination = toDir / entry.path().filename();
        fs::rename(entry.path(), destination, ec);

        if (ec) {
            ec.clear();
            copyRecursively(entry.path(), destination, ec);
            if (ec) {
                errorMessage = "failed to move path from " + entry.path().string() +
                               " to " + destination.string() + ", " + ec.message();
                return false;
            }

            fs::remove_all(entry.path(), ec);
            if (ec) {
                errorMessage = "failed to remove source after copy: " + entry.path().string() +
                               ", " + ec.message();
                return false;
            }
        }
    }

    return true;
}

void ZipExtractor::copyRecursively(const fs::path& from,
                                   const fs::path& to,
                                   std::error_code& ec) {
    if (fs::is_directory(from, ec)) {
        fs::create_directories(to, ec);
        if (ec) {
            return;
        }

        for (const auto& entry : fs::directory_iterator(from, ec)) {
            if (ec) {
                return;
            }

            copyRecursively(entry.path(), to / entry.path().filename(), ec);
            if (ec) {
                return;
            }
        }
        return;
    }

    if (fs::is_regular_file(from, ec)) {
        fs::create_directories(to.parent_path(), ec);
        if (ec) {
            return;
        }

        fs::copy_file(from, to, fs::copy_options::overwrite_existing, ec);
        return;
    }

    if (fs::is_symlink(from, ec)) {
        const fs::path linkTarget = fs::read_symlink(from, ec);
        if (ec) {
            return;
        }

        fs::create_directories(to.parent_path(), ec);
        if (ec) {
            return;
        }

        fs::create_symlink(linkTarget, to, ec);
    }
}

bool ZipExtractor::normalizeStripDuplicateRootOnce(const fs::path& tempRoot,
                                                   const fs::path& finalDir,
                                                   const fs::path& archivePath,
                                                   std::string& errorMessage) {
    const auto singleChildDir = getSingleChildDirectory(tempRoot);
    if (!singleChildDir.has_value()) {
        return moveChildren(tempRoot, finalDir, errorMessage);
    }

    const std::string childName = singleChildDir->path().filename().string();
    const std::string finalDirName = finalDir.filename().string();
    const std::string archiveBaseName = getArchiveBaseName(archivePath);

    if (childName == finalDirName || childName == archiveBaseName) {
        return moveChildren(singleChildDir->path(), finalDir, errorMessage);
    }

    return moveChildren(tempRoot, finalDir, errorMessage);
}

fs::path ZipExtractor::findDeepestSingleDirChainNode(const fs::path& root) {
    fs::path current = root;

    while (true) {
        auto singleChildDir = getSingleChildDirectory(current);
        if (!singleChildDir.has_value()) {
            break;
        }
        current = singleChildDir->path();
    }

    return current;
}

bool ZipExtractor::normalizeCollapseSingleDirChainKeepLeaf(const fs::path& tempRoot,
                                                           const fs::path& targetParent,
                                                           const fs::path& archivePath,
                                                           const bool overwrite,
                                                           fs::path& finalOutputPath,
                                                           std::string& errorMessage) {
    std::error_code ec;
    fs::create_directories(targetParent, ec);
    if (ec) {
        errorMessage = "failed to create target parent directory: " + targetParent.string() +
                       ", " + ec.message();
        return false;
    }

    const fs::path deepestNode = findDeepestSingleDirChainNode(tempRoot);

    if (deepestNode == tempRoot) {
        finalOutputPath = targetParent / getArchiveBaseName(archivePath);
        if (!prepareTarget(finalOutputPath, overwrite, errorMessage)) {
            return false;
        }
        return moveChildren(tempRoot, finalOutputPath, errorMessage);
    }

    finalOutputPath = targetParent / deepestNode.filename();
    if (!prepareTarget(finalOutputPath, overwrite, errorMessage)) {
        return false;
    }

    return moveChildren(deepestNode, finalOutputPath, errorMessage);
}

bool ZipExtractor::normalizeCollapseSingleDirChainDropLeaf(const fs::path& tempRoot,
                                                           const fs::path& targetDir,
                                                           const bool overwrite,
                                                           fs::path& finalOutputPath,
                                                           std::string& errorMessage) {
    finalOutputPath = targetDir;

    if (!prepareTarget(finalOutputPath, overwrite, errorMessage)) {
        return false;
    }

    const fs::path deepestNode = findDeepestSingleDirChainNode(tempRoot);

    if (deepestNode == tempRoot) {
        return moveChildren(tempRoot, finalOutputPath, errorMessage);
    }

    return moveChildren(deepestNode, finalOutputPath, errorMessage);
}
