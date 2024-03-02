package kdeps

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
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
    val url = line.toMavenUrl()
    downloadJar(url, outputDir.path)
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
