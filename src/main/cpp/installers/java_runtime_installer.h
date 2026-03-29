#pragma once

#include <iostream>

#include "../dto/install_java_runtime_result.h"

InstallJavaRuntimeResult installJavaRuntime();

int getEnvJavaRuntimeVersion();

int getPathJavaRuntimeVersion();

int parseJavaVersion(FILE*& pipe);