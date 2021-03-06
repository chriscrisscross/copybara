/*
 * Copyright (C) 2018 Google Inc.
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

package com.google.copybara.git;

import static com.google.copybara.config.SkylarkUtil.convertFromNoneable;
import static com.google.copybara.exception.ValidationException.checkCondition;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.copybara.Endpoint;
import com.google.copybara.LazyResourceLoader;
import com.google.copybara.exception.RepoException;
import com.google.copybara.exception.ValidationException;
import com.google.copybara.git.github.api.CreateStatusRequest;
import com.google.copybara.git.github.api.GitHubApi;
import com.google.copybara.git.github.api.Ref;
import com.google.copybara.git.github.api.Status;
import com.google.copybara.git.github.api.Status.State;
import com.google.copybara.git.github.api.UpdateReferenceRequest;
import com.google.copybara.git.github.util.GitHubUtil;
import com.google.copybara.util.console.Console;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.SkylarkList;

/** GitHub specific class used in feedback mechanism and migration event hooks to access GitHub */
@SuppressWarnings("unused")
@SkylarkModule(
  name = "github_api_obj",
  category = SkylarkModuleCategory.BUILTIN,
  doc =
      ""
          + "[EXPERIMENTAL] GitHub API endpoint implementation for feedback migrations and after "
          + "migration hooks."
)
public class GitHubEndPoint implements Endpoint {

  private LazyResourceLoader<GitHubApi> apiSupplier;
  private String url;
  private Console console;

  GitHubEndPoint(LazyResourceLoader<GitHubApi> apiSupplier, String url) {
    this.apiSupplier = Preconditions.checkNotNull(apiSupplier);
    this.url = Preconditions.checkNotNull(url);
    this.console = null;
  }

  private GitHubEndPoint(LazyResourceLoader<GitHubApi> apiSupplier, String url, Console console) {
    this.apiSupplier = Preconditions.checkNotNull(apiSupplier);
    this.url = Preconditions.checkNotNull(url);
    this.console = Preconditions.checkNotNull(console);
  }

  @SkylarkCallable(name = "create_status",
      doc = "Create or update a status for a commit. Returns the status created.",
      parameters = {
          @Param(name = "sha", type = String.class, named =  true,
              doc = "The SHA-1 for which we want to create or update the status"),
          @Param(name = "state", type = String.class, named =  true,
              doc = "The state of the commit status: 'success', 'error', 'pending' or 'failure'"),
          @Param(name = "context", type = String.class, doc = "The context for the commit"
              + " status. Use a value like 'copybara/import_successful' or similar",
              named =  true),
          @Param(name = "description", type = String.class, named = true,
              doc = "Description about what happened"),
          @Param(name = "target_url", type = String.class, named =  true,
              doc = "Url with expanded information about the event", noneable = true,
          defaultValue = "None"),
      },
      useLocation = true
  )
  public Status createStatus(
      String sha,
      String state,
      String context,
      String description,
      Object targetUrl,
      Location location) throws EvalException {
    try {
      checkCondition(State.VALID_VALUES.contains(state),
                     "Invalid value for state. Valid values: %s", State.VALID_VALUES);
      checkCondition(GitRevision.COMPLETE_SHA1_PATTERN.matcher(sha).matches(),
                     "Not a valid complete SHA-1: %s", sha);
      checkCondition(!Strings.isNullOrEmpty(description), "description cannot be empty");
      checkCondition(!Strings.isNullOrEmpty(context), "context cannot be empty");

      String project = GitHubUtil.getProjectNameFromUrl(url);
      return apiSupplier.load(console).createStatus(
          project, sha, new CreateStatusRequest(State.valueOf(state.toUpperCase()),
                                                 convertFromNoneable(targetUrl, null),
                                                 description, context));
    } catch (RepoException | ValidationException e) {
      throw new EvalException(location, e);
    }
  }

  @SkylarkCallable(name = "update_reference",
      doc = "Update a reference to point to a new commit. Returns the info of the reference.",
      parameters = {
          @Param(name = "ref", type = String.class, named =  true,
              doc = "The name of the reference."),
          @Param(name = "sha", type = String.class, doc = "The id for the commit"
              + " status.",
              named =  true),
          @Param(name = "force", type = Boolean.class, named =  true,
              doc = "Indicates whether to force the update or to "
                  + "make sure the update is a fast-forward update. Leaving this out or "
                  + "setting it to false will make sure you're not overwriting work. Default: false")

      },
      useLocation = true
  )
  public Ref updateReference(String sha, String ref, boolean force, Location location)
      throws EvalException {
    try {
      checkCondition(GitRevision.COMPLETE_SHA1_PATTERN.matcher(sha).matches(),
          "Not a valid complete SHA-1: %s", sha);
      checkCondition(!Strings.isNullOrEmpty(ref), "ref cannot be empty");

      String project = GitHubUtil.getProjectNameFromUrl(url);
      return apiSupplier.load(console).updateReference(
          project, ref, new UpdateReferenceRequest(sha, force));
    } catch (RepoException | ValidationException e) {
      throw new EvalException(location, e);
    }
  }

  @SkylarkCallable(name = "get_reference",
      doc = "Get a reference SHA-1 from GitHub",
      parameters = {
          @Param(name = "ref", type = String.class, named =  true,
              doc = "The name of the reference")
      },
      useLocation = true
  )
  public Ref getReference(String Ref, Location location) throws EvalException {
    try {
      checkCondition(!Strings.isNullOrEmpty(Ref), "Ref cannot be empty");

      String project = GitHubUtil.getProjectNameFromUrl(url);
      return apiSupplier.load(console).getReference(project, Ref);
    } catch (RepoException | ValidationException e) {
      throw new EvalException(location, e);
    }
  }

  @SkylarkCallable(
      name = "get_references",
      doc = "Get all the reference SHA-1s from GitHub. Note that Copybara only returns a maximum "
          + "number of 500.",
      useLocation = true
  )
  public SkylarkList<Ref> getReferences(Location location) throws EvalException {
    try {
      String project = GitHubUtil.getProjectNameFromUrl(url);
      return SkylarkList.createImmutable(apiSupplier.load(console).getReferences(project));
    } catch (RepoException | ValidationException e) {
      throw new EvalException(location, e);
    }
  }

  @SkylarkCallable(
      name = "url",
      doc = "Return the URL of this endpoint.",
      structField = true)
  public String getUrl() {
    return url;
  }

  @Override
  public GitHubEndPoint withConsole(Console console) {
    return new GitHubEndPoint(this.apiSupplier, this.url, console);
  }

  @Override
  public ImmutableSetMultimap<String, String> describe() {
    return ImmutableSetMultimap.of("type", "github_api", "url", url);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("url", url)
        .toString();
  }
}
