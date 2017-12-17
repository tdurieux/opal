/* BSD 2-Clause License:
 * Copyright (c) 2009 - 2017
 * Software Technology Group
 * Department of Computer Science
 * Technische Universität Darmstadt
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  - Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *  - Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package org.opalj
package br
package analyses

import com.typesafe.config.ConfigFactory
import com.typesafe.config.Config

/**
 * Factory to create `Config` objects with a given project type
 * (see [[org.opalj.ProjectTypes]]).
 *
 * This is particularly useful for testing purposes as it facilitates tests which
 * are independent of the current (default) configuration.
 */
object ProjectTypeConfigFactory {

    private[this] final def createConfig(mode: String) = {
        s"""org.opalj.project { type = "$mode"}"""
    }

    private[this] final def libraryConfig: String = {
        createConfig("library")
    }

    private[this] final def guiApplicationConfig: String = {
        createConfig("gui application")
    }

    private[this] final def commandLineApplicationConfig: String = {
        createConfig("command-line application")
    }

    private[this] final def jee6WebApplicationConfig: String = {
        createConfig("jee6+ web application")
    }

    def createConfig(value: ProjectType): Config = {
        import ProjectTypes._
        ConfigFactory.parseString(
            value match {
                case Library                ⇒ libraryConfig
                case CommandLineApplication ⇒ commandLineApplicationConfig
                case GUIApplication         ⇒ guiApplicationConfig
                case JEE6WebApplication     ⇒ jee6WebApplicationConfig
            }
        )
    }

    def resetProjectType[Source](
        project:                Project[Source],
        newProjectType:         ProjectType,
        useOldConfigAsFallback: Boolean         = true
    ): Project[Source] = {
        val testConfig = ProjectTypeConfigFactory.createConfig(newProjectType)
        Project.recreate(project, testConfig, useOldConfigAsFallback)
    }
}