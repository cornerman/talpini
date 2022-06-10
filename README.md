# terraverse

Use terraform with joy

Idea:
- Write and use normal terraform modules as usual
- Provide a yaml-based configuration approach to describe how to run and connect the terraform modules
- Similar to terragrunt, but tries to be an even thinner wrapper
- Not invasive

## CLI Usage

```
Usage: terraverse [<options>] [<command>]

Command: any terraform arguments

Options:
	-h|--help - show this message
	-v|--version - show version
	-t|--target <arg> - select target file/directory (default: ./). If you specify a file, it just runs this file (and dependencies). If you specify a directory, it recursively searches for any *.terraverse.yml (or yamL) file.
	-p|--parallelism <arg> - Specify the number of parallel runs. So yu can process independent groups of terraform modules in parallel. stdin is disabled - so you cannot confirm user-prompts from terraform.
	-y|--yes - automatically say yes to all terraverse prompts. This has no influence on terraform prompts. Example for apply without any user-prompts: terraverse -y --run-all apply -auto-approve
	-l|--log-level <arg> - select log level: trace, debug, info (default), warn, error
	--auto-include <arg> - per default files called terraverse.yml (or .yaml) above the directory are auto-included. You can add additional auto-include files, e.g. --auto-include dev.yml
	--terraform-cmd <arg> - set which terraform command to execute
	--run-all - run on all dependent configuration, not just the ones specified by target
	--no-cache - recreate cached terraform projects
```

You can set all CLI options with environment variables as well:
```
TERRAMIND_CLI_TARGET="<folder|file>"
TERRAMIND_CLI_PARALLELISM="<number>"
TERRAMIND_CLI_YES="true"
TERRAMIND_CLI_LOG_LEVEL="<trace|debug|info|warn|error>"
TERRAMIND_CLI_AUTO_INCLUDE="<filename>"
TERRAMIND_CLI_TERRAFORM_CMD="<terraform command>"
TERRAMIND_CLI_RUN_ALL="true"
TERRAMIND_CLI_NO_CACHE="true"
```

### Configuration File

TODO:
- Variable substitution (only allowed in sections: enabled, value, module, backend, providers, generate)
- inheritance (auto-includes, disable?) with includes with merge priority
- filenames: terraverse.yaml and <name>.terraverse.yaml and auto-includes
- Supported/recommended project structures

All terraverse yaml files follow this structure:
```
enabled: <Boolean>

includes:
    - <file or folder>
    - ...

generate:
    <filename>: <content>

copy:
    - <file or folder with globbing>
    - ...

values:
    <name>: <value>
    ...

dependencies:
    <name>: <file of depdendency yaml
    ...

backend:
    ...

module:
    ...

providers:
    <type>:
        - ...
```
