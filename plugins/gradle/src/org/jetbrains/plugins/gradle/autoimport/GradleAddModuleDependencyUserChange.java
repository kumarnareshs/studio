/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.plugins.gradle.autoimport;

import org.jetbrains.annotations.NotNull;

/**
 * @author Denis Zhdanov
 * @since 2/19/13 9:05 AM
 */
public class GradleAddModuleDependencyUserChange extends AbstractGradleDependencyUserChange<GradleAddModuleDependencyUserChange> {

  @SuppressWarnings("UnusedDeclaration")
  public GradleAddModuleDependencyUserChange() {
    // Necessary for IJ serialization.
  }

  public GradleAddModuleDependencyUserChange(@NotNull String moduleName, @NotNull String dependencyName) {
    super(moduleName, dependencyName);
  }

  @Override
  public void invite(@NotNull GradleUserProjectChangeVisitor visitor) {
    visitor.visit(this);
  }
  
  @Override
  public String toString() {
    return String.format("module '%s' is added as a dependency to module '%s'", getDependencyName(), getModuleName());
  }
}
