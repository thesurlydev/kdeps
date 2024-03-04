package kdeps

import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPath
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathExpression
import javax.xml.xpath.XPathFactory
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
    try {
        val pomContent = URI.create(pomUrl).toURL().readText()

        // write pom file to disk
        val pomDir = File("pom")
        if (!pomDir.exists()) {
            pomDir.mkdirs()
        }
        Files.write(Paths.get(pomDir.path, pomUrl.substringAfterLast("/")), pomContent.toByteArray())

        val allExcludes = mutableMapOf<String, MutableList<Pair<String, String>>>()

        val pomParseResult = parsePomForDependencies(pomContent)
        val dependencies = pomParseResult.dependencies
        for (pomPair in pomParseResult.excludes) {
            allExcludes.getOrPut(pomPair.key) { mutableListOf() }.addAll(pomPair.value)
        }

        for (exclude in allExcludes) {
            println("Excludes for ${exclude.key}: ${exclude.value}")
        }

        dependencies.forEach { (group, artifact, version) ->
            // only download if not excluded
            if (allExcludes.containsKey("$group:$artifact")) {
                val exclusionList = allExcludes["$group:$artifact"]!!
                if (exclusionList.any { (exclusionGroup, exclusionArtifact) ->
                        (exclusionGroup == group && exclusionArtifact == artifact) || (exclusionGroup == "*" && exclusionArtifact == "*")
                    }) {
                    println("Excluded: $group:$artifact:$version")
                    return@forEach
                }
            } else {
                downloadArtifact(group, artifact, version, directoryPath)
            }
        }
    } catch (e: IOException) {
        println("Failed to download or parse POM file: $e")
    }
}

fun parsePomForDependencies(pomContent: String): PomParseResult {
    val docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    val inputSource = org.xml.sax.InputSource(pomContent.reader())
    val doc = docBuilder.parse(inputSource)

    val xpathFactory: XPathFactory = XPathFactory.newInstance()
    val xpath: XPath = xpathFactory.newXPath()

    val parentNodeList: NodeList = doc.getElementsByTagName("parent")
    val projectVersion: String? = if (parentNodeList.length == 1) {
        val parentElement = parentNodeList.item(0) as org.w3c.dom.Element
        parentElement.getElementsByTagName("version").item(0).textContent
    } else {
        null
    }

    val dependencies = mutableListOf<Triple<String, String, String>>()
    val excludes = mutableMapOf<String, MutableList<Pair<String, String>>>()

    // only get project > dependencies > dependency nodes
    val projectNodeList = doc.getElementsByTagName("project")
    if (projectNodeList.length == 0) {
        return PomParseResult(dependencies, excludes)
    }
    val projectElement = projectNodeList.item(0) as org.w3c.dom.Element


    // XPath expression to select all dependency nodes not inside dependencyManagement
    val expression = "//dependency[not(ancestor::dependencyManagement)]"
    val expr: XPathExpression = xpath.compile(expression)

    val dependenciesNodeList = expr.evaluate(projectElement, XPathConstants.NODESET) as NodeList
    if (dependenciesNodeList.length == 0) {
        return PomParseResult(dependencies, excludes)
    }
    val dependenciesElement = dependenciesNodeList.item(0) as org.w3c.dom.Element

    val nodeList = dependenciesElement.getElementsByTagName("dependency")
    for (i in 0 until nodeList.length) {
        val node = nodeList.item(i)
        if (node.nodeType == Node.ELEMENT_NODE) {
            val element = node as org.w3c.dom.Element
            val groupId = element.getElementsByTagName("groupId").item(0).textContent
            val artifactId = element.getElementsByTagName("artifactId").item(0).textContent

            // skip provided, import, and test scope dependencies
            val excludeScopes = setOf("test", "import", "provided")
            val scopeNodeList = element.getElementsByTagName("scope")
            if (scopeNodeList.length == 1 && scopeNodeList.item(0).textContent.isNotBlank()) {
                val scope = scopeNodeList.item(0).textContent
                if (excludeScopes.contains(scope)) {
                    continue
                }
            }

            // version handling
            val verNodeList = element.getElementsByTagName("version")
            if (verNodeList.length == 0) {
                System.err.println("No version found for: $groupId:$artifactId")
                continue
            }
            if (verNodeList.length == 1) {
                var version = verNodeList.item(0).textContent
                if (version.isBlank()) {
                    System.err.println("Version was blank for: $groupId:$artifactId")
                    continue
                }

                if (version == "\${project.version}") {
                    if (projectVersion != null) {
                        version = projectVersion
                    } else {
                        System.err.println("Version was not resolved for: $groupId:$artifactId")
                        continue
                    }
                }

                // handle exclusions
                val exclusionNodeList = element.getElementsByTagName("exclusions")
                if (exclusionNodeList.length == 1) {
                    val exclusionElement = exclusionNodeList.item(0) as org.w3c.dom.Element
                    val exclusionList = exclusionElement.getElementsByTagName("exclusion")
                    for (j in 0 until exclusionList.length) {
                        val exElement = exclusionList.item(j) as org.w3c.dom.Element
                        val exclusionGroupId = exElement.getElementsByTagName("groupId").item(0).textContent
                        val exclusionArtifactId = exElement.getElementsByTagName("artifactId").item(0).textContent
                        excludes.getOrPut("$groupId:$artifactId:$version") { mutableListOf() }
                            .add(Pair(exclusionGroupId, exclusionArtifactId))
                    }
                }

                dependencies.add(Triple(groupId, artifactId, version))
            }
        }
    }
    return PomParseResult(dependencies, excludes)
}

data class PomParseResult(
    val dependencies: List<Triple<String, String, String>>,
    val excludes: Map<String, List<Pair<String, String>>>,
)

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
