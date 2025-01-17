package org.infinispan.cli.commands;

import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.infinispan.cli.activators.ConnectionActivator;
import org.infinispan.cli.completers.ContainerCompleter;
import org.infinispan.cli.impl.ContextAwareCommandInvocation;
import org.kohsuke.MetaInfServices;

/**
 * @author Tristan Tarrant &lt;tristan@infinispan.org&gt;
 * @since 10.0
 **/
@MetaInfServices(CliCommand.class)
@CommandDefinition(name = "container", description = "Selects a container", activator = ConnectionActivator.class)
public class Container extends CliCommand {

   @Argument(description = "The name of the container", completer = ContainerCompleter.class, required = true)
   String name;

   @Override
   public CommandResult exec(ContextAwareCommandInvocation invocation) {
      CommandInputLine cmd = new CommandInputLine("container").arg("name", name);
      return invocation.execute(cmd);
   }
}
