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

@file:JvmName("BundleTaskUtil")

package com.android.build.gradle.internal.tasks

import com.android.tools.build.bundletool.commands.BuildApksCommand
import com.android.tools.build.bundletool.model.SigningConfiguration
import com.android.tools.build.bundletool.utils.flags.Flag
import java.io.File
import java.util.Optional

private fun toPasswordFlag(password: String?): Optional<Flag.Password> =
    Optional.ofNullable(password?.let { Flag.Password.createFromFlagValue("pass:$it") })

internal fun BuildApksCommand.Builder.setSigningConfiguration(
    keystoreFile: File?, keystorePassword: String?, keyAlias: String?, keyPassword: String?):
 BuildApksCommand.Builder{
    if (keystoreFile == null) {
        return this
    }
    setSigningConfiguration(
        SigningConfiguration.extractFromKeystore(
            keystoreFile.toPath(),
            keyAlias,
            toPasswordFlag(keystorePassword),
            toPasswordFlag(keyPassword)
        )
    )
    return this
}