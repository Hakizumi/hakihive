#pragma once

#include <filesystem>
#include <optional>
#include <string>
#include <vector>

namespace fs = std::filesystem;

struct archive;
struct archive_entry;

class ZipExtractor {
public:
    enum class Mode {
        None,
        StripDuplicateRootOnce,
        CollapseSingleDirChainKeepLeaf,
        CollapseSingleDirChainDropLeaf
    };

    struct Options {
        Mode mode = Mode::StripDuplicateRootOnce;
        bool overwrite = true;
        bool preservePermissions = true;
        bool preserveTime = true;
    };

    struct Result {
        bool ok = false;
        fs::path outputPath;
        std::string errorMessage;
    };

    static Result extract(const fs::path& archivePath,
                          const fs::path& targetPath,
                          const Options& options = {Mode::None,true,true,true});

private:
    struct ScopeCleaner {
        fs::path path;
        bool dismissed = false;

        explicit ScopeCleaner(fs::path p);
        ~ScopeCleaner();
    };

    static fs::path makeTempDir(const std::string& hint);
    static bool prepareTarget(const fs::path& targetPath,
                              bool overwrite,
                              std::string& errorMessage);

    static bool extractRawTo(const fs::path& archivePath,
                             const fs::path& outputDir,
                             const Options& options,
                             std::string& errorMessage);

    static bool copyData(archive* reader,
                         archive* writer,
                         std::string& errorMessage);

    static fs::path sanitizeRelativePath(const fs::path& rawPath);

    static std::vector<fs::directory_entry> listImmediateChildren(const fs::path& dir);
    static std::optional<fs::directory_entry> getSingleChildDirectory(const fs::path& dir);
    static std::string getArchiveBaseName(const fs::path& archivePath);

    static bool moveChildren(const fs::path& fromDir,
                             const fs::path& toDir,
                             std::string& errorMessage);

    static void copyRecursively(const fs::path& from,
                                const fs::path& to,
                                std::error_code& ec);

    static bool normalizeStripDuplicateRootOnce(const fs::path& tempRoot,
                                                const fs::path& finalDir,
                                                const fs::path& archivePath,
                                                std::string& errorMessage);

    static bool normalizeCollapseSingleDirChainKeepLeaf(const fs::path& tempRoot,
                                                        const fs::path& targetParent,
                                                        const fs::path& archivePath,
                                                        bool overwrite,
                                                        fs::path& finalOutputPath,
                                                        std::string& errorMessage);

    static bool normalizeCollapseSingleDirChainDropLeaf(const fs::path& tempRoot,
                                                        const fs::path& targetDir,
                                                        bool overwrite,
                                                        fs::path& finalOutputPath,
                                                        std::string& errorMessage);

    static fs::path findDeepestSingleDirChainNode(const fs::path& root);
};
