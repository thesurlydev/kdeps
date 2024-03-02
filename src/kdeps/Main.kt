package kdeps

import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths

const val MAVEN_BASE_URL = "https://repo1.maven.org/maven2"
const val DEFAULT_LIB_DIR = "lib"

fun main(args: Array<String>) {
    val directoryPath = args.getOrNull(0) ?: DEFAULT_LIB_DIR
    val reader = System.`in`.bufferedReader()
    reader.forEachLine { line ->
        val url = line.toMavenUrl()
        downloadJar(url, directoryPath)
    }
    /*Files.readAllLines(java.nio.file.Paths.get(DEPS_FILE))
        .stream()
        .map(String::toMavenUrl)
        .forEach(::downloadJar)*/
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
