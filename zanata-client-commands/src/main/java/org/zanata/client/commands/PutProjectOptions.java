package org.zanata.client.commands;

import org.kohsuke.args4j.Option;

public interface PutProjectOptions extends ConfigurableOptions
{

   @Option(name = "--project-slug", metaVar = "PROJ", usage = "Project ID", required = true)
   public void setProjectSlug(String id);

   @Option(name = "--project-name", metaVar = "NAME", required = true, usage = "Project name")
   public void setProjectName(String name);

   @Option(name = "--project-desc", metaVar = "DESC", required = true, usage = "Project description")
   public void setProjectDesc(String desc);

   @Option(name = "--default-project-type", metaVar = "TYPE", required = true, usage = "Default project type")
   public void setDefaultProjectType(String type);

   public String getProjectSlug();

   public String getProjectDesc();

   public String getProjectName();

   public String getDefaultProjectType();

}