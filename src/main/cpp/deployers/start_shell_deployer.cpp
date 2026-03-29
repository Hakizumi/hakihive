#include "start_shell_deployer.h"

#include <fstream>
#include <string>

#include "../utils/platform_util.h"
#include "../dto/install_java_runtime_result.h"

using std::endl;

void deployStartShell(InstallJavaRuntimeResult java_runtime_result)
{
    const std::string os = detectOs();

    std::string javaCommand = java_runtime_result.useEnvironmentJava ?
            "java -jar hakihive.jar" : "runtime/bin/java.exe -jar hakihive.jar";

    if (os == "windows")
    {
        std::ofstream ofs("start.bat");

        if (!ofs.is_open())
        {
            throw std::runtime_error("Error opening start shell file.");
        }

        ofs << "@echo off" << endl;

        ofs << javaCommand << endl;
        ofs << "pause" << endl;

        ofs.close();
    }
    else if (os == "linux" || os == "mac")
    {
        std::ofstream ofs("start.sh");

        if (!ofs.is_open())
        {
            throw std::runtime_error("Error opening start shell file.");
        }

        ofs << "#!/bin/sh" << endl;
        ofs << javaCommand << endl;

        ofs.close();
    }
    else
    {
        throw std::runtime_error("Unsupported OS: " + os);
    }
}