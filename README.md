```
        ┏┓            ┓•     
        ┃ ┏┓┏┳┓┏┓┏┓┏┓┏┫┓┓┏┏┳┓
        ┗┛┗┛┛┗┗┣┛┗ ┛┗┗┻┗┗┻┛┗┗
          v1.4 ┛ by Constructor
```

Compendium is a command-line application,
written in Kotlin, designed to reallocate unique map IDs for each distinct map image in a Minecraft save file.
This process minimizes the dataset and eliminates map ID duplicates,
which is particularly useful
when merging multiple Minecraft worlds into a single one while maintaining a consistent map dataset.
Compendium generates a modified save file with the updated map data.

> **Minecraft Version Compatibility:** Compendium now supports Minecraft 1.21.1 format, including the new entity and map storage format.

## Key Features

- **Map ID Reallocation**: Compendium reads the map data, generates a unique hash for each map based on its image content, and creates a new mapping based on these hashes. This process eliminates map ID duplicates and minimizes the dataset.

- **Save File Duplication**: Compendium duplicates the Minecraft save file to a new location, allowing you to preserve the original save file while the duplicate is updated with the remapped map data.

- **Chunk Region File Reallocation**: Compendium scans all types of block entities such as chests, hoppers, and spawners for filled map items and remaps them.

- **Entity Region File Reallocation**: Compendium scans all types of entities like item frames and armor stands for filled map items and remaps them. It checks inventory and equipment slots, and also remaps items dropped on the ground.

- **Player Data Reallocation**: Compendium remaps player data in a Minecraft save file, including each player's inventory.

- **Level Data Reallocation**: Compendium remaps the level data in a Minecraft save file.

- **Minecraft 1.21.1 Support**: Compendium fully supports the new NBT format introduced in Minecraft 1.21.1, including the restructured entity format and the new component-based item storage for maps in item frames.

## Minecraft Version Compatibility

Compendium supports multiple Minecraft versions:

| Minecraft Version | Status | Notes |
|-------------------|--------|-------|
| 1.19.x - 1.20.4   | ✅ Fully Supported | All map formats supported |
| 1.21.1+           | ✅ Fully Supported | Supports new entity format and components-based item storage |

When working with Minecraft 1.21.1 worlds:
- Maps use a new storage format with the `components/minecraft:map_id` field
- Item frames store maps differently with dedicated `item_id` and `has_map` fields
- Unmapped maps will use negative ID values in the remapped world (consistent with pre-1.21.1 behavior)

## How to Use

To use Compendium, execute the application with the appropriate command-line arguments. Here is the basic usage:

```
java -jar Compendium-1.4.jar [OPTIONS]
```

The available options are:

- `--input <inputDir>`: Defines the input directory containing the files to be remapped. The default is 'save'.
- `--mapping <mappingFile>`: Defines the JSON mapping file used for remapping. The default is 'hash-mapping.json'.
- `--output <outputDir>`: Defines the output directory where the remapped files will be saved. The default is 'remapped'.
- `--dumpIDs`: Dumps the found and not found map IDs into separate text files in the output directory. (default: false)
- `--help`: Displays the help message.

## Example

Here is an example of how to use Compendium:

```
java -jar Compendium-1.4.jar --input 'my-save' --mapping 'my-mapping.json' --output 'my-remapped-save'
```

This command will remap the map items in the 'my-save' directory using the mappings in the 'my-mapping.json' file, and save the remapped files in the 'my-remapped-save' directory.

## Contributing

Contributions to Compendium are always welcome. Please ensure to adhere to the project's code style and write tests for any new features or bug fixes.

## License

Compendium is licensed under the GPLv3 License. For more details, refer to the LICENSE file.