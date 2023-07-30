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
- `--filter_accessors`: If true, Accessor and Invoker Mixins will not be listed in the Mixin details table (optional)
- `--create_graph`: If true, a graph showing the amount of Mixins per target for all targets with more than one Mixin is added to the Mixin dump (optional)
- `--extract_coremods`: If true, JS coremod configurations will be extracted from the mod JARs and listed per JAR in a separate `coremods.html` (optional)
- `--search_classes`: If true, all mods will be searched for any references to the classes listed in the `--target_classes` argument (optional)
- `--target_classes`: Comma-separated list of fully qualified class names to search for (required if `--search_classes` is true)
- `--ignored_classes`: Comma-separated list of fully qualified class names to ignore when searching for the targets (optional)
- `--dark`: Enable dark mode for the generated web page (optional)
- `--open_result`: If true, the resulting web page will be opened automatically in the default browser (optional)

The resulting `dependencies.html` and, if enabled with their respective arguments, `accesstransformers.html`, `mixins.html` and `coremods.html` files will be created in the application run directory.
