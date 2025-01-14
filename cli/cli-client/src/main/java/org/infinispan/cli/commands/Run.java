package org.infinispan.cli.commands;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;

import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.impl.completer.FileOptionCompleter;
import org.aesh.command.option.Arguments;
import org.aesh.io.Resource;
import org.infinispan.cli.impl.ContextAwareCommandInvocation;
import org.kohsuke.MetaInfServices;

/**
 * @author Tristan Tarrant &lt;tristan@infinispan.org&gt;
 * @since 10.0
 **/
@MetaInfServices(CliCommand.class)
@CommandDefinition(name = "run", description = "Reads and executes commands from one or more files")
public class Run extends CliCommand {
   @Arguments(required = true, completer = FileOptionCompleter.class)
   private List<Resource> arguments;

   @Override
   public CommandResult exec(ContextAwareCommandInvocation invocation) {
      if (arguments != null && arguments.size() > 0 && arguments.get(0).isLeaf()) {
         for (Resource resource : arguments) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(resource.read()))) {
               for (String line = br.readLine(); line != null; line = br.readLine()) {
                  if (!line.startsWith("#")) {
                     invocation.executeCommand("batch " + line);
                  }
               }
            } catch (Exception e) {
               throw new RuntimeException(e);
            }
         }
      }
      return CommandResult.SUCCESS;
   }
}
