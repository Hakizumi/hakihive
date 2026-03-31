cmake_minimum_required(VERSION 3.16)

project(Hakihive VERSION 1.0.1 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

file(GLOB_RECURSE SRC_FILES CONFIGURE_DEPENDS
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/*.cpp"
)

if (WIN32 AND NOT UNIX)
    add_executable(Hakihive ${SRC_FILES} assets/icons/app.rc)

    # Prevent macro definition pollution of Windows
    target_compile_definitions(Hakihive PRIVATE NOMINMAX WIN32_LEAN_AND_MEAN)
elseif (APPLE)
    add_executable(Hakihive MACOSX_BUNDLE ${SRC_FILES})
    set_target_properties(Hakihive PROPERTIES
            MACOSX_BUNDLE_ICON_FILE assets/icons/app.icns
    )
    set_source_files_properties(assets/icons/app.icns PROPERTIES MACOSX_PACKAGE_LOCATION "Resources")

    target_sources(Hakihive PRIVATE assets/icons/app.icns)
elseif (UNIX)
    add_executable(Hakihive ${SRC_FILES})
endif()

find_package(CURL REQUIRED)
find_package(nlohmann_json REQUIRED)
find_package(ftxui CONFIG REQUIRED)
find_package(OpenSSL REQUIRED)
find_package(LibArchive REQUIRED)

target_link_libraries(Hakihive PRIVATE
        CURL::libcurl
        nlohmann_json::nlohmann_json
        ftxui::screen
        ftxui::dom
        ftxui::component
        OpenSSL::SSL
        OpenSSL::Crypto
        LibArchive::LibArchive
)
