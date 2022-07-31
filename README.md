# talpini

Use terraform with joy

Idea:
- Write and use normal terraform modules as usual
- Provide a yaml-based configuration approach to describe how to run and connect the terraform modules
- Similar to terragrunt, but tries to be an even thinner wrapper
- Not invasive

## CLI Usage

```
Usage: talpini [<options>] [<command>]

Command: any terraform arguments.

Options:
	-h|--help - show this message.
	-v|--version - show version.
	-t|--target <arg> - Select target file/directory (default: ./). in case of a file, it only runs on this file. If you specify a directory, it recursively searches for any *.t.yml (or yaml) file.
	-p|--parallelism <arg> - Specify the number of parallel runs. So you can process independent groups of terraform modules in parallel. stdin is disabled - so you cannot confirm user-prompts from terraform.
	-y|--yes - Automatically say yes to all talpini prompts. This has no influence on terraform prompts. Example for apply without any user-prompts: talpini -y apply -auto-approve.
	-q|--quiet - Only write errors to stderr and plain terraform output to stdout. Set log-level to error.
	-l|--log-level <arg> - Select log level: trace, debug, info (default), warn, error.
	--auto-include <arg> - Per default files called talpini.yml (or .yaml) above the directory are auto-included. You can add additional auto-include files, e.g. --auto-include dev.yml.
	--init-arg <arg> - Set which args to append to terraform init.
	--terraform-cmd <arg> - Set which terraform command to execute.
	--run-all - Run on all dependent configuration, not just the ones specified by target.
	--no-cache - Recreate cached terraform projects.
```

You can set all CLI options with environment variables as well:
```
TALPINI_CLI_TARGET="<folder|file>"
TALPINI_CLI_PARALLELISM="<number>"
TALPINI_CLI_YES="true"
TALPINI_CLI_LOG_LEVEL="<trace|debug|info|warn|error>"
TALPINI_CLI_AUTO_INCLUDE="<filename>"
TALPINI_CLI_TERRAFORM_CMD="<terraform command>"
TALPINI_CLI_RUN_ALL="true"
TALPINI_CLI_NO_CACHE="true"
```

### Configuration File

TODO:
- Variable substitution (only allowed in sections: enabled, value, module, backend, providers, generate)
- inheritance (auto-includes, disable?) with includes with merge priority
- filenames: t.(yaml|yml) and <name>.t.(yaml|yml) and auto-includes
- Supported/recommended project structures

All t.yaml files follow this structure:
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
