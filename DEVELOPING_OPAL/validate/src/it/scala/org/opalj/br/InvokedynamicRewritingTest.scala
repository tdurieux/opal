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

import org.scalatest.FunSpec
import org.scalatest.Matchers
import java.net.URL
import java.net.URLClassLoader
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files

import org.opalj.bytecode.RTJar
import org.opalj.bi.TestResources.locateTestResources
import org.opalj.br.analyses.Project
import org.opalj.ba.ProjectBasedInMemoryClassLoader
import org.opalj.io.JARsFileFilter

/**
 * Tests if OPAL is able to rewrite invokedynamics using LambdaMetaFactory and checks if the
 * rewritten bytecode is executable.
 *
 * @author Andreas Muttscheller
 */
class InvokedynamicRewritingTest extends FunSpec with Matchers {

    def JavaFixtureProject(fixtureFiles: File): Project[URL] = {

        val projectClassFiles = Project.JavaClassFileReader().ClassFiles(fixtureFiles)
        val libraryClassFiles = Project.JavaLibraryClassFileReader.ClassFiles(RTJar)

        info(s"the test fixture project consists of ${projectClassFiles.size} class files")
        Project(
            projectClassFiles,
            libraryClassFiles,
            libraryClassFilesAreInterfacesOnly = false
        )
    }

    describe("behavior of rewritten bi.lambdas fixtures") {
        val r = locateTestResources("lambdas-1.8-g-parameters-genericsignature.jar", "bi")
        val p = JavaFixtureProject(r)
        val inMemoryClassLoader = new ProjectBasedInMemoryClassLoader(p)

        it("simpleLambdaAdd should calculate 2+2 correctly") {
            val c = inMemoryClassLoader.loadClass("lambdas.InvokeDynamics")
            val instance = c.newInstance()
            val m = c.getMethod("simpleLambdaAdd", Integer.TYPE, Integer.TYPE)
            val res = m.invoke(instance, new Integer(2), new Integer(2))

            assert(res.asInstanceOf[Integer] == 4)
        }

        it("serializedLambda should work with only objects properly") {
            val c = inMemoryClassLoader.loadClass("lambdas.methodreferences.IntersectionTypes")
            val m = c.getMethod("lambdaWithObjectCaptures")
            val res = m.invoke(null)

            assert(res.asInstanceOf[String] == "Hello World 23.14foo")
        }

        it("serializedLambda should work with object and primitives properly") {
            val c = inMemoryClassLoader.loadClass("lambdas.methodreferences.IntersectionTypes")
            val m = c.getMethod("lambdaWithObjectAndPrimitiveCaptures")
            val res = m.invoke(null)

            assert(res.asInstanceOf[String] == "Hello World 23.14foo")
        }

        it("serializedLambda should work with object array properly") {
            val c = inMemoryClassLoader.loadClass("lambdas.methodreferences.IntersectionTypes")
            val m = c.getMethod("lambdaWithObjectArray")
            val res = m.invoke(null)

            assert(res.asInstanceOf[String] == "Hello World 3.1442.0")
        }

        it("serializedLambda should work with primitive array properly") {
            val c = inMemoryClassLoader.loadClass("lambdas.methodreferences.IntersectionTypes")
            val m = c.getMethod("lambdaWithPrimitiveArray")
            val res = m.invoke(null)

            assert(res.asInstanceOf[String] == "Hello World 3.1442.0")
        }

        it("serializedLambda should work with primitive array and object properly") {
            val c = inMemoryClassLoader.loadClass("lambdas.methodreferences.IntersectionTypes")
            val m = c.getMethod("lambdaWithPrimitiveArrayAndObject")
            val res = m.invoke(null)

            assert(res.asInstanceOf[String] == "Hello World 3.1442.0foo")
        }
    }

    describe("behavior of rewritten JCG lambda_expressions project") {

        it("should execute main successfully") {
            val p = JavaFixtureProject(
                locateTestResources("classfiles/jcg_lambda_expressions.jar", "bi")
            )
            val inMemoryClassLoader = new ProjectBasedInMemoryClassLoader(p)

            val c = inMemoryClassLoader.loadClass("app.ExpressionPrinter")
            val m = c.getMethod("main", classOf[Array[String]])

            // Intercept output
            val baos = new ByteArrayOutputStream()
            val defaultOut = System.out
            System.setOut(new PrintStream(baos))

            m.invoke(null, Array("lambda_expressions.jar"))
            assert(baos.toString == "Id(((1)++)²)\n")

            // Reset System.out
            System.setOut(defaultOut)
        }
    }

    describe("behavior of rewritten OPAL") {

        it("should execute Hermes successfully") {
            val p = JavaFixtureProject(
                locateTestResources("classfiles/OPAL-MultiJar-SNAPSHOT-01-04-2018.jar", "bi")
            )
            val scalaLib =
                locateTestResources("classfiles/scala-2.12.4/", "bi").
                    listFiles(JARsFileFilter).
                    map(_.toURI.toURL)
            val opalDependencies =
                locateTestResources(
                    "classfiles/OPAL-MultiJar-SNAPSHOT-01-04-2018-dependencies/", "bi"
                ).
                    listFiles(JARsFileFilter).
                    map(_.toURI.toURL)

            // Otherwise, the hermes resources are not included and hermes won't find
            // HermesCLI.txt for example
            val resourceClassloader = new URLClassLoader(
                Array(
                    new File("DEVELOPING_OPAL/tools/src/main/resources/").toURI.toURL,
                    new File("OPAL/ai/src/main/resources/").toURI.toURL,
                    new File("OPAL/ba/src/main/resources/").toURI.toURL,
                    new File("OPAL/bi/src/main/resources/").toURI.toURL,
                    new File("OPAL/bp/src/main/resources/").toURI.toURL,
                    new File("OPAL/br/src/main/resources/").toURI.toURL,
                    new File("OPAL/common/src/main/resources/").toURI.toURL
                ) ++ scalaLib ++ opalDependencies,
                null
            )
            val inMemoryClassLoader = new ProjectBasedInMemoryClassLoader(p, resourceClassloader)

            val c = inMemoryClassLoader.loadClass("org.opalj.hermes.HermesCLI")
            val m = c.getMethod("main", classOf[Array[String]])

            val tempFile = Files.createTempFile("OPALValidate-Hermes-stats-", ".csv").toFile
            tempFile.delete()

            m.invoke(null, Array(
                "-config", "DEVELOPING_OPAL/validate/src/it/resources/hermes-test-fixtures.json",
                "-statistics", tempFile.getAbsolutePath
            ))

            assert(tempFile.exists())
            assert(tempFile.length() > 0)
        }
    }
}