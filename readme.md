# SortLog

Sortlog is a simple utility for processing logs from FTC RC and DS apps to remove duplicate log entries as
well as trivial system log messages.

usage: sortlog [-tidy|-notidy] [inputFile]

inputFile - the name of the logcat file to sort. The output
            has the same name but with '.sorted' appended. If inputFile
            is absent, then stdin is sorted, and the output is written
            to stdout.

-[no]tidy - when tidying, superfluous lines such as garbage collector messages are removed

To run the tool, execute 'java -jar sortlog.jar [inputFile]' at the command line.

To build the tool, please install IntelliJ IDEA (ie: NOT Android Studio), open the project, and
select Build/Build Artifacts... from the menu