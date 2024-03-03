package kdeps

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.system.exitProcess

const val MAVEN_BASE_URL = "https://repo1.maven.org/maven2"
const val DEFAULT_LIB_DIR = "lib"

fun main(args: Array<String>) {

    // Parse "-f" for input file
    val fileIndex = args.indexOf("-f")
    val filePath = if (fileIndex != -1 && args.size > fileIndex + 1) args[fileIndex + 1] else null

    // Parse "-o" for output directory
    val dirIndex = args.indexOf("-o")
    val outputDirPath = if (dirIndex != -1 && args.size > dirIndex + 1) args[dirIndex + 1] else DEFAULT_LIB_DIR

    // Ensure the output directory exists
    val outputDir = File(outputDirPath)
    if (!outputDir.exists()) {
        outputDir.mkdirs()
    }

    if (filePath != null) {
        // Process input from file
        val file = File(filePath)
        if (file.exists() && file.canRead()) {
            file.forEachLine { line ->
                processLine(line, outputDir) // Process and output to specified directory
            }
        } else {
            System.err.println("File does not exist or cannot be read: $filePath")
            exitProcess(1)
        }
    } else {
        // Process input from System.in
        System.`in`.bufferedReader().forEachLine { line ->
            processLine(line, outputDir) // Process and output to specified directory
        }
    }

    exitProcess(0)
}

fun processLine(line: String, outputDir: File) {
    val (group, artifact, version) = line.split(":")
    downloadArtifact(group, artifact, version, outputDir.path)
}

val processedDependencies = mutableSetOf<String>()

fun downloadArtifact(group: String, artifact: String, version: String, directoryPath: String) {
    val identifier = "$group:$artifact:$version"
    if (identifier in processedDependencies) {
        println("Dependency already processed: $identifier")
        return
    }

    processedDependencies.add(identifier) // Mark this dependency as processed

    val jarUrl = identifier.toMavenUrl()
    downloadJar(jarUrl, directoryPath) // Download the JAR file

    val pomUrl = identifier.toPomUrl()
    downloadPomAndProcessDependencies(pomUrl, directoryPath)
}

fun downloadPomAndProcessDependencies(pomUrl: String, directoryPath: String) {
    println("Downloading: $pomUrl")
    // Similar implementation to downloadJar but for POM files
    // After downloading, parse the POM file to find dependencies
    try {
        val pomContent = URI.create(pomUrl).toURL().readText()
        val dependencies = parsePomForDependencies(pomContent)
        dependencies.forEach { (group, artifact, version) ->
            downloadArtifact(group, artifact, version, directoryPath)
        }
    } catch (e: IOException) {
        println("Failed to download or parse POM file: $e")
    }
}

fun parsePomForDependencies(pomContent: String): List<Triple<String, String, String>> {
    val docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    val inputSource = org.xml.sax.InputSource(pomContent.reader())
    val doc = docBuilder.parse(inputSource)
    val dependencies = mutableListOf<Triple<String, String, String>>()
    val nodeList = doc.getElementsByTagName("dependency")
    for (i in 0 until nodeList.length) {
        val node = nodeList.item(i)
        if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
            val element = node as org.w3c.dom.Element
            val groupId = element.getElementsByTagName("groupId").item(0).textContent
            val artifactId = element.getElementsByTagName("artifactId").item(0).textContent
            val version = element.getElementsByTagName("version").item(0).textContent
            dependencies.add(Triple(groupId, artifactId, version))
        }
    }
    return dependencies
}

fun String.toPomUrl(): String {
    val (group, artifact, version) = split(":")
    val path = group.replace(".", "/")
    return "$MAVEN_BASE_URL/$path/$artifact/$version/$artifact-$version.pom"
}

// transform 'org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0' to 'https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.0/kotlinx-coroutines-core-jvm-1.8.0.jar'
fun String.toMavenUrl(): String {
    val (group, artifact, version) = split(":")
    val path = group.replace(".", "/")
    return "$MAVEN_BASE_URL/$path/$artifact/$version/$artifact-$version.jar"
}

fun downloadJar(urlString: String, directoryPath: String) {
    println("Downloading: $urlString")
    if (!Paths.get(directoryPath).toFile().exists()) {
        Files.createDirectory(Paths.get(directoryPath))
    }
    val fileName = urlString.substringAfterLast("/")
    val localFile = Paths.get(directoryPath, fileName).toFile()
    if (localFile.exists()) {
        println("File already exists: $fileName")
        return
    }
    try {
        val url = URI.create(urlString).toURL()
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.inputStream.use { inputStream ->
            FileOutputStream(localFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        println("Download completed: $fileName")
    } catch (e: IOException) {
        println("Failed to download file: $e")
    }
}
