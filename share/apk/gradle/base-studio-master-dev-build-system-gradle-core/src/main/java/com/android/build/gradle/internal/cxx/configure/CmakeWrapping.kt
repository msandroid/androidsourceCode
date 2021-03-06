/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.build.gradle.internal.cxx.configure

import com.android.SdkConstants
import java.io.File
import com.android.build.gradle.internal.cxx.configure.CmakeProperty.*
import com.android.builder.model.Version

private const val HEADER_TEXT = "This file is generated by Android Studio Gradle plugin. Do not modify."

/**
 * Write a toolchain file that calls back into the original toolchain file. Afterwards, it checks
 * whether the expected cache entry exists. If it does exist then it will use compiler settings
 * from that file.
 *
 * @param originalToolchainFile the original toolchain that is being wrapped.
 *
 * @param cacheFile path to a CMake-language file that contains the compiler cache settings
 *   originally discovered about the current build environment.
 *
 * @param cacheUseSignalFile this file is always written. It specifies whether the cache was used
 *   or not. If it was used then, downstream, there's not point in recording build variables
 *   since they are already the result of a cache hit.
 *
 * Returns a string which has the Cmake-language toolchain wrapper.
 */
fun wrapCmakeToolchain(
    originalToolchainFile: File,
    cacheFile: File,
    cacheUseSignalFile : File) = wrapCmakeToolchain(
        convertWindowsBackslashToCmakeForwardSlashes(originalToolchainFile),
        convertWindowsBackslashToCmakeForwardSlashes(cacheFile),
        convertWindowsBackslashToCmakeForwardSlashes(cacheUseSignalFile))

private fun wrapCmakeToolchain(
    originalToolchainFile: String,
    cacheFile: String,
    cacheUseSignalFile : String): String {
    return """
        # $HEADER_TEXT
        include("$originalToolchainFile")
        if (EXISTS "$cacheFile")
          if (NOT EXISTS "$cacheUseSignalFile")
            message("Using compiler settings cached by Android Gradle Plugin")
          endif()
          if (NOT DEFINED $ANDROID_GRADLE_BUILD_COMPILER_SETTINGS_CACHE_USED)
            file(WRITE "$cacheUseSignalFile" "{ \"isCacheUsed\": true }")
            set($ANDROID_GRADLE_BUILD_COMPILER_SETTINGS_CACHE_USED true)
            set($CMAKE_C_COMPILER_FORCED true)
            set($CMAKE_CXX_COMPILER_FORCED true)
            include("$cacheFile")
          endif()
        else()
          set($ANDROID_GRADLE_BUILD_COMPILER_SETTINGS_CACHE_USED false)
          file(WRITE "$cacheUseSignalFile" "{ \"isCacheUsed\": false }")
        endif()"""
        .trimIndent()
        .replace("\n", System.lineSeparator()) // Kotlin always emits \n
}

/**
 * Emit a CMakeLists.txt that includes the original CMakeLists.txt folder and then records
 * all build variables at the end.
 *
 * The wrapped CMakeLists.txt writes a Json file to disk that can be read later by Android
 * Gradle plugin. Json has special escaping requirements. For example, equal-sign (=) must
 * be translated to \U003d. This is the reason for the replacements like:
 *
 *   string(REPLACE "=" "\\u003d" value "${value}")
 *
 * @param gradleBuildOutputFolder the root output folder like
 *   ./externalNativeBuild/cmake/debug/x86.
 *
 * @param buildGenerationStateFile the file to write CMake build variables to. Schema is
 *   CmakeBuildGenerationState.
 *
 * @param originalCmakeListsFolder folder of original CMakeLists.txt that is being wrapped.
 *
 * Return a string which has the CMake-language wrapper.
 */
fun wrapCmakeLists(
    originalCmakeListsFolder: File,
    gradleBuildOutputFolder: File,
    buildGenerationStateFile: File,
    isWindows: Boolean = IS_WINDOWS) = wrapCmakeLists(
        convertWindowsBackslashToCmakeForwardSlashes(originalCmakeListsFolder),
        convertWindowsBackslashToCmakeForwardSlashes(gradleBuildOutputFolder),
        convertWindowsBackslashToCmakeForwardSlashes(buildGenerationStateFile),
        isWindows)

private fun wrapCmakeLists(
    originalCmakeListsFolder: String,
    gradleBuildOutputFolder: String,
    buildGenerationStateFile: String,
    isWindows: Boolean): String {
    val newline = if (isWindows) {
        "\\r\\n"
    } else {
        "\\n"
    }
    return """
        # $HEADER_TEXT
        cmake_minimum_required(VERSION 3.4.1)
        add_subdirectory("$originalCmakeListsFolder" "$gradleBuildOutputFolder" )
        function(android_gradle_build_write_build_variables)
          get_cmake_property(variableNames VARIABLES)
          set(build_variables_file "$buildGenerationStateFile")
          list(SORT variableNames)
          file(WRITE ${'$'}{build_variables_file} "{$newline  \"properties\": [$newline")
          foreach (variableName ${'$'}{variableNames})
            set(value "${'$'}{${'$'}{variableName}}")
            string(REPLACE "\\" "\\u005c" value "${'$'}{value}")
            string(REPLACE "\r" "\\u000a" value "${'$'}{value}")
            string(REPLACE "\n" "\\u000d" value "${'$'}{value}")
            string(REPLACE "\"" "\\u0022" value "${'$'}{value}")
            string(REPLACE "=" "\\u003d" value "${'$'}{value}")
            file(APPEND ${'$'}{build_variables_file} "    {\"name\" : \"${'$'}{variableName}\", ")
            file(APPEND ${'$'}{build_variables_file} "\"value\" : \"${'$'}{value}\"},$newline")
          endforeach()
          file(APPEND ${'$'}{build_variables_file} "    {\"name\" : \"$ANDROID_GRADLE_BUILD_COMPILER_SETTINGS_CACHE_RECORDED_VERSION\", \"value\" : \"${Version.ANDROID_GRADLE_PLUGIN_VERSION}\" } ] }$newline")
        endfunction()
        android_gradle_build_write_build_variables()"""
        .trimIndent()
        .replace("\n", System.lineSeparator()) // Kotlin always emits \n
}

/**
 * Replace literal values with their corresponding CMake variable names in a block of CMake
 * code.
 */
fun substituteCmakePaths(block : String, androidNdk : File) : String {
    return block.replace(
        convertWindowsBackslashToCmakeForwardSlashes(androidNdk), "\${$ANDROID_NDK}")
}

/**
 * CMake language tolerates Windows back-slashes inconsistently. This function converts back slashes
 * to forward slashes which are acceptable everywhere. This results in paths like:
 *
 *   c:/path/to/file
 *
 * But this is okay for CMake.
 */
private fun convertWindowsBackslashToCmakeForwardSlashes(file : File) : String {
    return file.toString().replace("\\", "/")
}

private val IS_WINDOWS = SdkConstants.currentPlatform() == SdkConstants.PLATFORM_WINDOWS
