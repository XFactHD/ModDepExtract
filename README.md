# ModDepExtract

ModDepExtract is a CLI tool to extract dependency information and other details from mod files.

## Features

- Extract dependency information from the mod JARs and validate the resulting dependency matrix
- Extract AccessTransformer configuration from the mod JARs and check for problematic ATs
- Extract Mixin configuration from the mod JARs
- Extract JS coremod configuration from the mod JARs

## Usage

Execute the built JAR from a command line interface with the following arguments:

- `--minecraft`: The Minecraft version the mods are run with
- `--forge`: The Forge version the mods are run with
- `--directory`: The game directory as configured in the official launcher. The "mods" folder is expected to exist in this directory
- `--onlySatisfied`: If true, only mods where all dependencies are satisfied will be listed in the results
- `--onlyUnsatisfied`: If true, only mods where at least one dependency is not satisfied will be listed in the results
- `--extract_ats`: If true, AccessTransformer configurations will be extracted from the mod JARs and listed per JAR in a separate `accesstransformers.html` (optional)
- `--flagged_ats`:
  - Comma-separated list of simplified AT targets (method or field name without enclosing class, simple class name without package)
  - Useful to find out which mod breaks a coremod by ATing a field the coremod operates on (`FluidBlock#fluid` is a prominent example)
  - The given list most not contain spaces
  - optional, only available when `--extract_ats` is set
- `--extract_mixins`: If true, Mixin configurations will be extracted from the mod JARs and listed per JAR in a separate `mixins.html` (optional)
- `--extract_coremods`: If true, JS coremod configurations will be extracted from the mod JARs and listed per JAR in a separate `coremods.html` (optional)
- `--decompiler_path`: Provide the path to a ForgeFlower decompiler JAR (optional)
  - If not provided, a JAR with the name "forgeflower-1.5.498.29.jar" is expected to be in the application's run directory
  - Usage of FernFlower or other forks of FernFlower may or may not provide satisfactory results
  - If a custom path is provided but the JAR can't be found, a warning will be logged
  - If the decompiler is not found, an info will be logged and the following features will not be available
    - Retrieval of the correct internal name of language providers
    - Mixin target deduction
- `--dark`: Enable dark mode for the generated web page (optional)
- `--open_result`: If true, the resulting web page will be opened automatically in the default browser (optional)

The resulting `dependencies.html` and, if enabled with their respective arguments, `accesstransformers.html`, `mixins.html` and `coremods.html` files will be created in the application run directory.
