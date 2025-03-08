package me.constructor

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    var inputDir = "save"
    var mappingFile = "hash-mapping.json"
    var outputDir = "remapped"
    var dumpIDs = false

    if (args.contains("--help")) {
        printHelp()
        exitProcess(0)
    }

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--input" -> {
                if (i + 1 < args.size) {
                    inputDir = args[i + 1]
                    i++
                }
            }
            "--mapping" -> {
                if (i + 1 < args.size) {
                    mappingFile = args[i + 1]
                    i++
                }
            }
            "--output" -> {
                if (i + 1 < args.size) {
                    outputDir = args[i + 1]
                    i++
                }
            }
            "--dumpIDs" -> dumpIDs = true
        }
        i++
    }
    
    println("Using input directory: $inputDir")

    Compendium(inputDir, mappingFile, outputDir, dumpIDs)
}

fun printHelp() {
    println("Usage: java -jar compendium.jar [OPTIONS]")
    println("Options:")
    println("  --input <inputDir>: Specifies the input directory containing the files to be remapped. (default: save)")
    println("  --mapping <mappingFile>: Specifies the JSON mapping file used for remapping. (default: hash-mapping.json)")
    println("  --output <outputDir>: Specifies the output directory where the remapped files will be saved. (default: remapped)")
    println("  --dumpIDs: Dumps the found and not found map IDs into separate text files in the output directory. (default: false)")
    println("  --help: Prints this help message")
}
