/*
Copyright (c) 2016 Robert Atkinson

All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted (subject to the limitations in the disclaimer below) provided that
the following conditions are met:

Redistributions of source code must retain the above copyright notice, this list
of conditions and the following disclaimer.

Redistributions in binary form must reproduce the above copyright notice, this
list of conditions and the following disclaimer in the documentation and/or
other materials provided with the distribution.

Neither the name of Robert Atkinson nor the names of his contributors may be used to
endorse or promote products derived from this software without specific prior
written permission.

NO EXPRESS OR IMPLIED LICENSES TO ANY PARTY'S PATENT RIGHTS ARE GRANTED BY THIS
LICENSE. THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESSFOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR
TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
package ftc.engineering.sortlog;

import edu.umd.cs.findbugs.annotations.Nullable;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;

import java.io.*;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * SortLog is a simple utility designed for sorting Android logcat files produced by the FTC SDK runtime.
 * It's primary purpose is to deal with that such logs can contain multiple copies of prefixes of the
 * same log, which can be confusing. We sort the log and eliminate duplicates, at the same time being careful
 * to preserve relative order of lines that have the same sort key.
 * <p>
 * The sort key is the first 18 characters of each line. Example log fragments include the following:
 * <p>
 * 02-19 11:40:49.125 I/FIRST   (11806): Stopping FTC Controller Service
 * 02-19 11:40:49.125 V/RobotCore(11806): There are 0 Wifi Direct Assistant Clients (-)
 * 02-19 11:40:49.125 V/RobotCore(11806): Disabling Wifi Direct Assistant
 * 02-19 11:40:49.135 D/RobotCore(11806): RobocolDatagramSocket is closed
 *
 * That's old; current layout is thus:
 *
 * 01-01 01:47:07.199 15883 16254 E RobotCore: thread id=63 name="lynx async work"
 * 01-01 01:47:07.200 15883 16254 E RobotCore:     at java.lang.Object.wait(Native Method)
 * 01-01 01:47:07.200 15883 16254 E RobotCore:     at java.lang.Thread.parkFor$(Thread.java:1220)
 * 01-01 01:47:07.200 15883 16254 E RobotCore:     at sun.misc.Unsafe.park(Unsafe.java:299)
 * 0123456789012345678901234567890
 * 0         1         2         3
 *
 * <p>
 * There are occasional other lines in the log, such as lines like
 * <p>
 * --------- beginning of /dev/log/main
 * <p>
 * which denote the beginning of a log restart. We ignore the difference of those; they'll (typically)
 * sort at the beginning. Likewise, we sort lines that are of insufficient length at the start as well.
 */
@SuppressWarnings("WeakerAccess")
public class Main
    {
    //------------------------------------------------------------------------------------------------------------------
    // State
    //------------------------------------------------------------------------------------------------------------------

    public static class Key implements Comparable<Key>
        {
        Main main;
        Date date;
        int  month;
        int  dayOfMonth;
        int  process;
        int  thread;
        int  processIndex;

        static SimpleDateFormat formattedDate = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
        static Calendar calendar = Calendar.getInstance(TimeZone.getDefault(), Locale.US);

        static {
            formattedDate.setTimeZone(TimeZone.getDefault());
            }

        public Key(Main main)
            {
            this.main = main;
            this.date = new Date(0);
            this.process = 0;
            this.thread = 0;
            initialize();
            }

        public Key(Main main, Date date, int process, int thread)
            {
            this.main = main;
            this.date = date;
            this.process = process;
            this.thread = thread;
            initialize();
            }

        protected void initialize()
            {
            calendar.setTime(date);
            month = calendar.get(Calendar.MONTH);
            dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH);
            processIndex = main.processes.indexOf(this.process);
            if (processIndex < 0)
                {
                main.processes.add(this.process);
                processIndex = main.processes.indexOf(this.process);
                }
            }

        public static Key from(Main main, String line, @Nullable Key prevKey)
            {
            Key result = null;

            if (line.startsWith(logStartString))
                {
                result = prevKey;  // keep log start messages in relative order
                }
            else if (line.length() >= cchKeyDate)
                {
                ParsePosition parsePosition = new ParsePosition(0);
                Date date = formattedDate.parse(line, parsePosition);
                if (date != null)
                    {
                    String rest = line.substring(parsePosition.getIndex()).trim();
                    String[] splits = rest.split(" +");
                    int process = Integer.parseInt(splits[0]);
                    int thread = Integer.parseInt(splits[1]);
                    result = new Key(main, date, process, thread);
                    }
                }

            if (result==null)
                {
                result = new Key(main); // old: key = "00-00";  // Rogue line: sort at the start
                }

            return result;
            }

        @Override public boolean equals(Object obj)
            {
            if (obj instanceof Key)
                {
                Key them = (Key)obj;
                return this.date.equals(them.date) && this.process==them.process && this.thread==them.thread;
                }
            return false;
            }

        @Override public int hashCode()
            {
            return Objects.hash(this.date, this.process, this.thread);
            }

        @Override public int compareTo(Key them)
            {
            int result = 0;
            if (this.isControlHub() && them.isControlHub())
                {
                result = this.processIndex - them.processIndex;
                if (result == 0)
                    {
                    result = this.date.compareTo(them.date);
                    }
                }
            else
                {
                result = this.date.compareTo(them.date);
                }
            return result;
            }

        public boolean isControlHub()
            {
            return this.month == Calendar.JANUARY && this.dayOfMonth == 1;
            }
        }


    public static final int cchKeyDate = 30;
    public static final String logStartString = "--------- beginning of";

    protected boolean                   doTidy = true;
    protected Map<Key, List<String>>    lines  = new HashMap<>();
    protected List<String>              fileArgs = new LinkedList<>();
    protected File                      outputFile = null;
    protected ArrayList<Integer>        processes = new ArrayList<>();

    //------------------------------------------------------------------------------------------------------------------
    // Invocation
    //------------------------------------------------------------------------------------------------------------------

    public static void usage()
        {
        System.err.println("usage: sortlog [-tidy|-notidy] [-out outputFile] [inputFile]+");
        System.err.println();
        System.err.println("inputFile - a name of a logcat file to sort (wildcards supported). By default, output");
        System.err.println("            has the same name as the first input but with '.sorted' appended.");
        System.err.println();
        System.err.println("-[no]tidy - when tidying, superfluous lines such as garbage collector messages are removed");
        System.exit(-1);
        }

    //------------------------------------------------------------------------------------------------------------------
    // Operations
    //------------------------------------------------------------------------------------------------------------------

    public static void main(String[] args)
        {
        (new Main()).doMain(args);
        }

    protected void parseArgs(String[] args)
        {
        for (int i = 0; i < args.length; i++)
            {
            String arg = args[i];
            if (arg.equals("-?") || arg.equals("/?"))
                usage();
            else if (arg.equals("-tidy") || arg.equals("/tidy"))
                doTidy = true;
            else if (arg.equals("-notidy") || arg.equals("/notidy"))
                doTidy = false;
            else if (arg.equals("-out") || arg.equals("/out"))
                {
                i++;
                if (i < args.length)
                    {
                    outputFile = new File(args[i]);
                    }
                else
                    usage();
                }
            else
                {
                fileArgs.add(arg);
                }
            }
        }

    protected void doMain(String[] args)
        {
        // Figure out what we've been asked to do
        parseArgs(args);

        // Expand any wildcards in the file names (though the OS might have done this for us)
        // and add the lines of each of those to our list
        File currentDirectory = new File(".");
        for (String fileArg : fileArgs)
            {
            for (File file : FileUtils.listFiles(currentDirectory, new WildcardFileFilter(fileArg), null))
                {
                if (outputFile==null)
                    {
                    outputFile = new File(file.getAbsolutePath() + ".sorted");
                    }

                try
                    {
                    if (!file.getCanonicalPath().equals(outputFile.getCanonicalPath()))
                        {
                        processFile(file);
                        }
                    }
                catch (IOException ignored)
                    {
                    }
                }
            }

        // Process the output
        if (outputFile != null)
            {
            emitOutput();
            }
        }

    protected void processFile(File inputFile)
        {
        System.out.println(String.format("reading '%s'", inputFile.getName()));

        try
            {
            InputStream fis = new FileInputStream(inputFile);
            if (isGZIPFile(inputFile))
                {
                fis = new GZIPInputStream(fis);
                }

            InputStreamReader isr = new InputStreamReader(fis);
            BufferedReader br = new BufferedReader(isr);
            Key prevKey = new Key(this);

            for(int iLine=0;;iLine++)
                {
                // Read in the next line of the file. Stop if we've reached the end
                String line = br.readLine();
                if (line == null)
                    break;

                while (line.startsWith("\0"))
                    {
                    line = line.substring(1);
                    }

                // Ignore the line, maybe, if we are tidying
                if (doTidy)
                    {
                    if (line.contains("GC_CONCURRENT freed"))
                        continue;
                    }

                Key key = Key.from(this, line, prevKey);

                // Find the list of lines with that sort key
                List<String> list = lines.get(key);
                if (list == null)
                    {
                    list = new LinkedList<String>();
                    lines.put(key, list);
                    }

                // Add a new line under that sort key. Be sure to Maintain the order in which we encounter lines with
                // the same key. But avoid storing the same line more than once.
                if (!list.contains(line))
                    {
                    list.add(line);
                    }

                prevKey = key;
                }

            // Tidy up our input
            br.close();
            isr.close();
            fis.close();
            }
        catch (FileNotFoundException e)
            {
            System.err.printf("file \"%s\" not found\n", inputFile.getName());
            }
        catch (IOException e)
            {
            System.err.printf("IOException thrown\n");
            }
        }
    
    protected void emitOutput()
        {
        // Sort the sort keys into the right order
        List<Key> sortedKeys = new LinkedList<Key>(lines.keySet());
        Collections.sort(sortedKeys);

        // Get our hands on the output file
        System.out.println(String.format("emitting '%s'", outputFile.getName()));

        try
            {
            PrintStream out = new PrintStream(new FileOutputStream(outputFile), true);

            // Output all the lines in the right order
            for (Key key : sortedKeys)
                {
                for (String line : lines.get(key))
                    {
                    out.printf("%s\n", line);
                    }
                }

            // Tidy up
            out.close();
            }
        catch(FileNotFoundException e)
            {
            System.err.printf("file \"%s\" not found\n", outputFile.getName());
            }
        }

    protected boolean isGZIPFile(File file)
        {
        return Pattern.compile(".*\\.gz", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(file.getName())
                .matches();
        }
    }
