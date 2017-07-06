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
package bi
package reader

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.BufferedInputStream
import java.io.IOException
import java.util.zip.{ZipEntry, ZipFile}
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

import scala.util.control.ControlThrowable
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.Future

import org.apache.commons.text.similarity.LevenshteinDistance

import org.opalj.log.OPALLogger
import org.opalj.log.GlobalLogContext
import org.opalj.control.repeat
import org.opalj.io.process
import org.opalj.concurrent.OPALExecutionContextTaskSupport
import org.opalj.concurrent.NumberOfThreadsForIOBoundTasks
import org.opalj.bytecode.BytecodeProcessingFailedException

/**
 * Implements the template method to read in a Java class file. Additionally,
 * several convenience methods are defined to read in class files from various
 * sources (Streams, Files, JAR archives).
 *
 * This library supports class files from version 45 (Java 1.1) up to version 53 (Java 9).
 *
 * ==Notes for Implementors==
 * Reading of the class file's major structures: the constant pool, fields, methods
 * and the attributes is delegated to corresponding readers.
 * This enables a very high-level of adaptability.
 *
 * For further details see the JVM Specification: The ClassFile Structure.
 *
 * @author Michael Eichberg
 */
trait ClassFileReader extends ClassFileReaderConfiguration with Constant_PoolAbstractions {

    import ClassFileReader.{ExceptionHandler, defaultExceptionHandler}

    //
    // ABSTRACT DEFINITIONS
    //

    /**
     * The type of the object that represents a Java class file.
     */
    type ClassFile

    /**
     * The type of the object that represents the fields of a class.
     */
    type Fields

    /**
     * The type of the object that represents all methods of a class.
     */
    type Methods

    /**
     * The type of the object that represents a class declaration's
     * attributes (e.g., the source file attribute.)
     */
    type Attributes

    // METHODS DELEGATING TO OTHER READERS
    //

    /**
     * Reads the constant pool using the given stream.
     *
     * When this method is called the given stream has to be positioned at the very
     * beginning of the constant pool. This method is called by the template method that
     * reads in a class file to delegate the reading of the constant pool. Only
     * information belonging to the constant pool are allowed to be read.
     *
     * The stream must not be closed after reading the constant pool.
     */
    protected def Constant_Pool(in: DataInputStream): Constant_Pool

    /**
     * Reads all field declarations using the given stream and constant pool.
     *
     * The given stream is positioned directly before a class file's "fields_count" field.
     * This method is called by the template method that reads in a class file to
     * delegate the reading of the declared fields.
     */
    protected def Fields(cp: Constant_Pool, in: DataInputStream): Fields

    /**
     * Reads all method declarations using the given stream and constant pool.
     *
     * The given stream is positioned directly before a class file's "methods_count" field.
     * This method is called by the
     * template method that reads in a class file to delegate the reading of the
     * declared method.
     */
    protected def Methods(cp: Constant_Pool, in: DataInputStream): Methods

    /**
     * Reads all attributes using the given stream and constant pool.
     *
     * The given stream is positioned directly before a class file's "attributes_count"
     * field.
     * This method is called by the template method that reads in a class file to
     * delegate the reading of the attributes.
     *
     * '''From the Specification'''
     *
     * The attributes [...] appearing in the attributes table of a ClassFile
     * structure are the InnerClasses, EnclosingMethod, Synthetic, Signature,
     * SourceFile, SourceDebugExtension, Deprecated, RuntimeVisibleAnnotations,
     * RuntimeInvisibleAnnotations, BootstrapMethods, RuntimeVisibleTypeAnnotations,
     * and RuntimeInvisibleTypeAnnotations attributes.
     */
    protected def Attributes(
        ap: AttributeParent,
        cp: Constant_Pool,
        in: DataInputStream
    ): Attributes

    /**
     * Factory method to create the `ClassFile` object that represents the class
     * file as a whole, plus any `ClassFile`s that have been synthesized in the process
     * of parsing it.
     *
     * The result will always contain at least one `ClassFile` object, namely the one that
     * is created from this method's parameters. Regardless of how many `ClassFile`s the
     * result contains, the `ClassFile` created from this method's parameters will always
     * be the result's first element.
     */
    protected def ClassFile(
        cp:            Constant_Pool,
        minor_version: Int,
        major_version: Int,
        access_flags:  Int,
        this_class:    Constant_Pool_Index,
        super_class:   Constant_Pool_Index,
        interfaces:    IndexedSeq[Constant_Pool_Index],
        fields:        Fields,
        methods:       Methods,
        attributes:    Attributes
    ): ClassFile

    //
    // IMPLEMENTATION
    //

    private[this] var classFilePostProcessors: List[List[ClassFile] ⇒ List[ClassFile]] = Nil

    /**
     * Register a class file post processor. A class file post processor
     * can transform the completely read and reified class file. Post processors
     * can only be registered before the usage of a class file reader. '''Registering
     * new `ClassFilePostProcessors` while processing class files is not supported
     * and the behavior is undefined'''.
     *
     * @note `PostProcessors` will be executed in last-in-first-out order.
     */
    def registerClassFilePostProcessor(p: List[ClassFile] ⇒ List[ClassFile]): Unit = {
        classFilePostProcessors = p :: classFilePostProcessors
    }

    /**
     * Template method that reads a Java class file from the given input stream.
     *
     * All other methods to read a class file use this method to eventually parse a
     * class file.
     *
     * ==Class File Structure==
     * Parses a class file according to the specification:
     * <pre>
     * ClassFile {
     *    u4 magic;
     *    u2 minor_version;
     *    u2 major_version;
     *    u2 constant_pool_count;
     *    cp_info constant_pool[constant_pool_count-1];
     *    u2 access_flags;
     *    u2 this_class;
     *    u2 super_class;
     *    u2 interfaces_count;
     *    u2 interfaces[interfaces_count];
     *    u2 fields_count;
     *    field_info fields[fields_count];
     *    u2 methods_count;
     *    method_info methods[methods_count];
     *    u2 attributes_count;
     *    attribute_info attributes[attributes_count];
     * }
     * </pre>
     *
     * @param in    The `DataInputStream` from which the class file will be read. The
     *              stream is not closed by this method.
     *              '''It is highly recommended that the stream is buffered; otherwise the
     *              performance will be terrible!'''
     */
    def ClassFile(in: DataInputStream): List[ClassFile] = {
        // magic
        val readMagic = in.readInt
        if (ClassFileMagic != readMagic)
            throw BytecodeProcessingFailedException("the file does not start with 0xCAFEBABE")

        val minor_version = in.readUnsignedShort
        val major_version = in.readUnsignedShort

        // let's make sure that we support this class file's version
        if (!(
            major_version >= 45 && // at least JDK 1.1
            (major_version < 53 /* Java 8 = 52.0 */ ||
                (major_version == 53 && minor_version == 0 /*Java 9 == 53.0*/ ))
        )) throw BytecodeProcessingFailedException(
            s"unsupported class file version: $major_version.$minor_version"+
                " (Supported: 45(Java 1.1) <= version <= 53(Java 9))"
        )

        val cp = Constant_Pool(in)
        val access_flags = in.readUnsignedShort
        val this_class = in.readUnsignedShort
        val super_class = in.readUnsignedShort
        val interfaces = {
            val interfaces_count = in.readUnsignedShort
            repeat(interfaces_count) { in.readUnsignedShort }
        }
        val fields = Fields(cp, in)
        val methods = Methods(cp, in)
        val attributes = Attributes(AttributesParent.ClassFile, cp, in)

        var classFile = ClassFile(
            cp,
            minor_version, major_version,
            access_flags,
            this_class, super_class, interfaces,
            fields, methods,
            attributes
        )

        // Perform transformations that are specific to this class file.
        // (Used, e.g., to finally resolve the invokedynamic instructions.)
        classFile = applyDeferredActions(cp, classFile)

        // Perform general transformations on class files.
        var classFiles = List(classFile)
        classFilePostProcessors foreach { postProcessor ⇒
            classFiles = postProcessor(classFiles)
        }

        classFiles
    }

    //
    // CONVENIENCE METHODS TO LOAD CLASS FILES FROM VARIOUS SOURCES
    //

    /**
     * Reads in a class file.
     *
     * @param   create A function that creates a new `InputStream` and
     *          which must not return `null`. If you already do have an open input stream
     *          which should not be closed after reading the class file use
     *          `...ClassFileReader.ClassFile(java.io.DataInputStream) : ClassFile` instead.
     *          The (newly created) `InputStream` returned by calling `create` is closed by
     *          this method.
     *          The created input stream will automatically be wrapped by OPAL to enable
     *          efficient reading of the class file.
     */
    def ClassFile(create: () ⇒ InputStream): List[ClassFile] = {
        process(create()) {
            case null ⇒
                throw new IllegalArgumentException("the created stream is null")

            case dis: DataInputStream      ⇒ ClassFile(dis)
            case bis: BufferedInputStream  ⇒ ClassFile(new DataInputStream(bis))
            case bas: ByteArrayInputStream ⇒ ClassFile(new DataInputStream(bas))
            case is ⇒
                ClassFile(new DataInputStream(new BufferedInputStream(is)))
        }
    }

    def isClassFileRepository(filename: String): Boolean = {
        filename.endsWith(".jar") || filename.endsWith(".zip") ||
            filename.endsWith(".war") || filename.endsWith(".ear")
    }

    protected[this] def ClassFile(jarFile: ZipFile, jarEntry: ZipEntry): List[ClassFile] = {
        process(jarFile.getInputStream(jarEntry)) { in ⇒
            ClassFile(new DataInputStream(new BufferedInputStream(in)))
        }
    }

    /**
     * Reads in a single class file from a Jar file.
     *
     * @param jarFile An existing ZIP/JAR file that contains class files.
     * @param jarFileEntryName The name of a class file stored in the specified ZIP/JAR file.
     */
    @throws[java.io.IOException]("if the file is empty or the entry cannot be found")
    def ClassFile(jarFile: File, jarFileEntryName: String): List[ClassFile] = {
        if (jarFile.length() == 0)
            throw new IOException(s"the file $jarFile is empty");

        val levenshteinDistance = new LevenshteinDistance()

        process(new ZipFile(jarFile)) { zf ⇒
            val jarEntry = zf.getEntry(jarFileEntryName)
            if (jarEntry == null) {
                var names: List[(Int, String)] = Nil
                val zfEntries = zf.entries()
                while (zfEntries.hasMoreElements) {
                    val zfEntry = zfEntries.nextElement()
                    val zfEntryName = zfEntry.getName
                    val distance = levenshteinDistance(zfEntryName, jarFileEntryName).intValue()
                    names = (distance, zfEntryName) :: names
                }
                val mostRelatedNames = names.sortWith((l, r) ⇒ l._1 < r._1).map(_._2).take(15)
                val ending = if (mostRelatedNames.length > 15) ", ...)" else ")"
                val messageHeader = s"the file $jarFile does not contain $jarFileEntryName"
                val message = mostRelatedNames.mkString(s"$messageHeader (similar: ", ", ", ending)
                throw new IOException(message)
            }
            ClassFile(zf, jarEntry)
        }
    }

    /**
     * Reads in a single class file from a Jar file.
     *
     * @param jarFileName the name of an existing ZIP/JAR file that contains class files.
     * @param jarFileEntryName the name of a class file stored in the specified ZIP/JAR file.
     */
    @throws[java.io.IOException]("if the file is empty or the entry cannot be found")
    def ClassFile(jarFileName: String, jarFileEntryName: String): List[ClassFile] = {
        ClassFile(new File(jarFileName), jarFileEntryName)
    }

    /**
     * Reads in parallel all class files stored in the given jar/zip file.
     *
     * @param jarFile Some valid (non-empty) jar File.
     * @return The loaded class files.
     */
    def ClassFiles(
        jarFile:          ZipFile,
        exceptionHandler: ExceptionHandler
    ): List[(ClassFile, URL)] = {
        val mutex = new Object
        var classFiles: List[(ClassFile, URL)] = Nil

        def addClassFile(cf: ClassFile, url: URL) =
            mutex.synchronized {
                classFiles = (cf, url) :: classFiles
            }

        ClassFiles(jarFile, addClassFile, exceptionHandler)
        classFiles
    }

    /**
     * Reads '''in parallel''' all class files stored in the given jar file. For each
     * successfully read class file the function `classFileHandler` is called.
     *
     * @param jarFile A valid jar file that contains `.class` files and other
     *     `.jar` files; other files are ignored. Inner jar files are also unzipped.
     * @param classFileHandler A function that is called for each class file in
     *      the given jar file.
     *      Given that the jarFile is read in parallel '''this function has to be
     *      thread safe'''.
     * @param exceptionHandler The exception handler that is called when the reading
     *      of a class file fails. '''This function has to be thread safe'''.
     */
    def ClassFiles(
        jarFile:          ZipFile,
        classFileHandler: (ClassFile, URL) ⇒ Unit,
        exceptionHandler: ExceptionHandler
    ): Unit = {
        val jarFileURL = new File(jarFile.getName).toURI.toURL.toExternalForm
        val jarFileName = s"jar:$jarFileURL!/"
        ClassFiles(jarFileName, jarFile, classFileHandler, exceptionHandler)
    }

    private def ClassFiles(
        jarFileURL:       String, // the complete path to the given jar file.
        jarFile:          ZipFile,
        classFileHandler: (ClassFile, URL) ⇒ Unit,
        exceptionHandler: ExceptionHandler
    ): Unit = {

        import scala.collection.JavaConversions._

        // First let's collect all inner Jar Entries, then do the processing.
        // Otherwise - if the OPALExecutionContextTaskSupport uses a fixed
        // sized thread pool - we may run out of threads... to process anything.
        val innerJarEntries = new ConcurrentLinkedQueue[ZipEntry]

        val jarEntries = jarFile.entries.toArray
        val nextEntryIndex = new AtomicInteger(jarEntries.length - 1)
        val parallelismLevel = NumberOfThreadsForIOBoundTasks
        val futures: Array[Future[Unit]] = new Array(parallelismLevel)
        var futureIndex = 0
        while (futureIndex < parallelismLevel) {
            futures(futureIndex) = Future[Unit] {
                var index = -1
                while ({ index = nextEntryIndex.getAndDecrement; index } >= 0) {
                    val jarEntry = jarEntries(index)
                    if (!jarEntry.isDirectory && jarEntry.getSize > 0) {
                        val jarEntryName = jarEntry.getName
                        if (jarEntryName.endsWith(".class")) {
                            try {
                                val url = new URL(jarFileURL + jarEntry.getName)
                                val classFiles = ClassFile(jarFile, jarEntry)
                                classFiles foreach (classFile ⇒ classFileHandler(classFile, url))
                            } catch {
                                case ct: ControlThrowable ⇒ throw ct
                                case t: Throwable         ⇒ exceptionHandler(jarEntryName, t)
                            }
                        } else if (isClassFileRepository(jarEntryName)) {
                            innerJarEntries.add(jarEntry)
                        }
                    }
                }
            }(org.opalj.concurrent.OPALExecutionContext)
            futureIndex += 1
        }
        while ({ futureIndex -= 1; futureIndex } >= 0) {
            Await.ready(futures(futureIndex), Duration.Inf)
        }

        for (jarEntry ← innerJarEntries.iterator()) {
            val nextJarFileURL = s"${jarFileURL}jar:${jarEntry.getName}!/"
            try {
                val jarData = new Array[Byte](jarEntry.getSize.toInt)
                val din = new DataInputStream(jarFile.getInputStream(jarEntry))
                din.readFully(jarData)
                din.close()
                ClassFiles(nextJarFileURL, jarData, classFileHandler, exceptionHandler)
            } catch {
                case ct: ControlThrowable ⇒ throw ct
                case t: Throwable         ⇒ exceptionHandler(nextJarFileURL, t)
            }
        }
    }

    /**
     * Loads class files from an in-memory representation of a jar file given in form
     * of a byte array.
     * This is done by writing the jar file data to a temporary file and then loading
     * the class files from it as done with any other jar file.
     */
    private def ClassFiles(
        jarFileURL:       String,
        jarData:          Array[Byte],
        classFileHandler: (ClassFile, URL) ⇒ Unit,
        exceptionHandler: ExceptionHandler
    ): Unit = {
        val pathToEntry = jarFileURL.substring(0, jarFileURL.length - 3)
        val entry = pathToEntry.substring(pathToEntry.lastIndexOf('/') + 1)
        try {
            val jarFile = File.createTempFile(entry, ".zip")

            process { new java.io.FileOutputStream(jarFile) } { fout ⇒ fout.write(jarData) }
            ClassFiles(jarFileURL, new ZipFile(jarFile), classFileHandler, exceptionHandler)

            jarFile.delete()
        } catch {
            case ct: ControlThrowable ⇒ throw ct
            case t: Throwable         ⇒ exceptionHandler(pathToEntry, t)
        }
    }

    /**
     * Loads class files from the given file location. If the file denotes
     * a single ".class" file this class file is loaded. If the file
     * object denotes a ".jar|.war|.ear|.zip" file, all class files in the jar file will be loaded.
     * If the file object specifies a directory object, all ".class" files
     * in the directory and in all subdirectories are loaded as well as all
     * class files stored in ".jar" files in one of the directories. This class loads
     * all class files in parallel. However, this does not effect analyses working on the
     * resulting `List`.
     */
    def ClassFiles(
        file:             File,
        exceptionHandler: ExceptionHandler = defaultExceptionHandler
    ): List[(ClassFile, URL)] = {

        def processJar(file: File): List[(ClassFile, URL)] = {
            try {
                process(new ZipFile(file)) { zf ⇒ ClassFiles(zf, exceptionHandler) }
            } catch {
                case e: Exception ⇒
                    exceptionHandler(file, new IOException("cannot process: "+file, e))
                    Nil
            }
        }

        def processClassFile(file: File): List[(ClassFile, URL)] = {
            try {
                process(
                    new DataInputStream(new BufferedInputStream(new FileInputStream(file)))
                ) { in ⇒ ClassFile(in).map(classFile ⇒ (classFile, file.toURI.toURL)) }
            } catch {
                case e: Exception ⇒
                    exceptionHandler(file, new IOException(s"cannot process $file", e))
                    Nil
            }
        }

        if (!file.exists()) {
            Nil
        } else if (file.isFile) {
            val filename = file.getName
            if (file.length() == 0) Nil
            else if (isClassFileRepository(filename)) processJar(file)
            else if (filename.endsWith(".class")) processClassFile(file)
            else Nil
        } else if (file.isDirectory) {
            var jarFiles = List.empty[File]
            var classFiles = List.empty[File]
            def collectFiles(files: Array[File]): Unit = {
                if (files eq null)
                    return ;

                files.foreach { file ⇒
                    val filename = file.getName
                    if (file.isFile) {
                        if (file.length() == 0) Nil
                        else if (isClassFileRepository(filename)) jarFiles ::= file
                        else if (filename.endsWith(".class")) classFiles ::= file
                    } else if (file.isDirectory) {
                        collectFiles(file.listFiles())
                    } else {
                        throw new IOException(s"$file is neither a file nor a directory")
                    }
                }
            }
            // 1. get the list of all files in the directory as well as all subdirectories
            collectFiles(file.listFiles())

            // 2. get all class files
            var allClassFiles = List.empty[(ClassFile, URL)]

            // 2.1 load - in parallel - all ".class" files
            if (classFiles.nonEmpty) {
                import scala.collection.JavaConverters._
                val theClassFiles = new ConcurrentLinkedQueue[(ClassFile, URL)]
                val parClassFiles = classFiles.par
                parClassFiles.tasksupport = OPALExecutionContextTaskSupport
                parClassFiles.foreach { classFile ⇒
                    theClassFiles.addAll(processClassFile(classFile).asJava)
                }
                allClassFiles ++= theClassFiles.asScala
            }

            // 2.2 load - one after the other - all ".jar" files (processing jar files
            //    is already parallelized.)
            jarFiles.foreach { jarFile ⇒ allClassFiles ++= processJar(jarFile) }

            // 3. return all loaded class files
            allClassFiles
        } else {
            throw new IOException(s"$file is neither a file nor a directory")
        }
    }

    def AllClassFiles(
        files:            Traversable[File],
        exceptionHandler: ExceptionHandler  = defaultExceptionHandler
    ): Traversable[(ClassFile, URL)] = {
        files.flatMap(file ⇒ ClassFiles(file, exceptionHandler))
    }
}
/**
 * Helper methods related to reading class files.
 *
 * @author Michael Eichberg
 */
object ClassFileReader {

    type ExceptionHandler = (AnyRef, Throwable) ⇒ Unit

    final val defaultExceptionHandler: ExceptionHandler = (source, t) ⇒ {
        OPALLogger.error("reading class files", s"processing $source failed", t)(GlobalLogContext)
    }

}
