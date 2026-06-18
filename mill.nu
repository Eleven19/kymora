#!/usr/bin/env nu

# Cross-platform Nushell launcher for Mill.
#
# This intentionally mirrors the behavior of the upstream `mill` shell launcher
# and `mill.bat`: discover a Mill version, resolve the right downloadable
# artifact for the current platform, cache it locally, and exec it with the
# compatibility `mill.main.cli` property.

const default_mill_version = "1.2.0-RC1"
const mill_repo_url = "https://github.com/com-lihaoyi/mill"
const native_suffix = "-native"
const jvm_suffix = "-jvm"
const script_path = path self

# Read the first logical line from a version file, normalizing CRLF input.
def first-line [file: string] {
  open --raw $file
  | str replace -a "\r" "\n"
  | lines
  | first
  | str trim
}

# Extract a scalar version value from YAML or magic-comment style declarations.
def trim-version-value [line: string] {
  $line
  | split row ":"
  | last
  | split row "#"
  | first
  | str replace -a "'" ""
  | str replace -a '"' ""
  | str trim
}

# Return the build script path used for legacy magic-comment version discovery.
def find-build-script [] {
  if ("build.mill" | path exists) {
    "build.mill"
  } else if ("build.mill.scala" | path exists) {
    "build.mill.scala"
  } else if ("build.sc" | path exists) {
    "build.sc"
  } else {
    ""
  }
}

# Parse `//| mill-version: ...` declarations from a classic Mill build script.
def version-from-build-script [file: string] {
  if $file == "" {
    ""
  } else {
    let matches = (
      open --raw $file
      | lines
      | where $it =~ '//\|.*mill-version'
      | first 1
    )

    if ($matches | is-empty) {
      ""
    } else {
      trim-version-value ($matches | get 0)
    }
  }
}

# Discover the requested Mill version using the same precedence as the bundled launchers.
def discover-version [] {
  if (($env.MILL_VERSION? | default "") != "") {
    $env.MILL_VERSION
  } else if (".mill-version" | path exists) {
    first-line ".mill-version"
  } else if (".config/mill-version" | path exists) {
    first-line ".config/mill-version"
  } else if ("build.mill.yaml" | path exists) {
    let parsed = (open build.mill.yaml)
    ($parsed | get --optional mill-version | default "")
  } else {
    version-from-build-script (find-build-script)
  }
}

# Return true when `value` starts with any prefix from `prefixes`.
def starts-with-any [value: string, prefixes: list<string>] {
  $prefixes | any {|prefix| $value | str starts-with $prefix }
}

# Older Mill releases only ship JVM launchers unless explicitly suffixed.
def old-version-without-native [version: string] {
  starts-with-any $version [
    "0.1."
    "0.2."
    "0.3."
    "0.4."
    "0.5."
    "0.6."
    "0.7."
    "0.8."
    "0.9."
    "0.10."
    "0.11."
    "0.12."
  ]
}

# Check whether the host Linux GLIBC is new enough for Mill native binaries.
def check-glibc-version [] {
  let result = (ldd --version | complete)
  if $result.exit_code != 0 {
    return false
  }

  let line = ($result.stdout | lines | first | default "")
  let versions = ($line | parse -r '(?P<version>[0-9]+\.[0-9]+)$')
  if ($versions | is-empty) {
    return false
  }

  let parts = (($versions | get 0.version) | split row ".")
  let major = (($parts | get 0) | into int)
  let minor = (($parts | get 1) | into int)

  ($major > 2) or ($major == 2 and $minor >= 39)
}

# Resolve the platform-specific native artifact suffix, or empty for JVM fallback.
def native-artifact-suffix [] {
  let os = $nu.os-info.name
  let arch = $nu.os-info.arch

  if $os == "linux" {
    if not (check-glibc-version) {
      ""
    } else if $arch == "aarch64" {
      "-native-linux-aarch64"
    } else {
      "-native-linux-amd64"
    }
  } else if $os == "macos" {
    if $arch == "aarch64" or $arch == "arm64" {
      "-native-mac-aarch64"
    } else {
      "-native-mac-amd64"
    }
  } else if $os == "windows" {
    if $arch == "aarch64" or $arch == "arm64" {
      ""
    } else {
      "-native-windows-amd64"
    }
  } else {
    print --stderr "This native mill launcher supports only Linux, macOS, and Windows."
    exit 1
  }
}

# Convert the requested version into the download version, artifact suffix, and cache extension.
def resolve-artifact [raw_version: string] {
  mut mill_version = $raw_version
  mut artifact_suffix = ""
  mut cache_ext = if $nu.os-info.family == "windows" { ".bat" } else { "" }

  if ($mill_version | str ends-with $native_suffix) {
    $mill_version = ($mill_version | str substring ..<(-1 * ($native_suffix | str length)))
    $artifact_suffix = (native-artifact-suffix)
  } else if ($mill_version | str ends-with $jvm_suffix) {
    $mill_version = ($mill_version | str substring ..<(-1 * ($jvm_suffix | str length)))
  } else if not (old-version-without-native $mill_version) {
    $artifact_suffix = (native-artifact-suffix)
  }

  if $nu.os-info.family == "windows" and $artifact_suffix != "" {
    $cache_ext = ".exe"
  }

  {
    mill_version: $mill_version
    artifact_suffix: $artifact_suffix
    cache_ext: $cache_ext
  }
}

# Return the base user cache directory used for Mill downloads.
def default-cache-root [] {
  if (($env.XDG_CACHE_HOME? | default "") != "") {
    $env.XDG_CACHE_HOME
  } else {
    let home = (
      if $nu.os-info.family == "windows" {
        $env.USERPROFILE? | default ($env.HOME? | default ".")
      } else {
        $env.HOME? | default "."
      }
    )
    [$home ".cache"] | path join
  }
}

# Build the cached launcher file name, preserving Windows `.bat`/`.exe` behavior.
def cache-file-name [raw_version: string, resolved: record] {
  if $nu.os-info.family == "windows" {
    $"($raw_version)($resolved.cache_ext)"
  } else {
    $"($resolved.mill_version)($resolved.artifact_suffix)"
  }
}

# Return true when the Mill distribution artifact should be downloaded from Maven Central.
def should-download-from-maven [version: string] {
  if (starts-with-any $version ["0.0." "0.1." "0.2." "0.3." "0.4."]) {
    false
  } else if (starts-with-any $version ["0.5." "0.6." "0.7." "0.8." "0.9." "0.10."]) {
    false
  } else if ($version | str starts-with "0.11.0-M") {
    false
  } else {
    true
  }
}

# Return the GitHub release asset suffix used by older Mill versions.
def download-suffix [version: string] {
  if (starts-with-any $version ["0.0." "0.1." "0.2." "0.3." "0.4."]) {
    ""
  } else {
    "-assembly"
  }
}

# Return the distribution file extension for the requested Mill version.
def download-extension [version: string] {
  if (starts-with-any $version [
    "0.12.0"
    "0.12.1"
    "0.12.2"
    "0.12.3"
    "0.12.4"
    "0.12.5"
    "0.12.6"
    "0.12.7"
    "0.12.8"
    "0.12.9"
    "0.12.10"
    "0.12.11"
  ]) {
    "jar"
  } else if ($version | str starts-with "0.12.") {
    "exe"
  } else if ($version | str starts-with "0.") {
    "jar"
  } else {
    "exe"
  }
}

# Derive the GitHub release tag for versions hosted outside Maven Central.
def version-tag [version: string] {
  let parts = ($version | split row "-")
  let base = ($parts | get 0)
  let milestone = ($parts | get 1? | default "")

  if ($milestone | str starts-with "M") {
    $"($base)-($milestone)"
  } else {
    $base
  }
}

# Build the canonical download URL for the resolved Mill artifact.
def download-url [version: string, artifact_suffix: string] {
  let ext = (download-extension $version)

  if (should-download-from-maven $version) {
    $"https://repo1.maven.org/maven2/com/lihaoyi/mill-dist($artifact_suffix)/($version)/mill-dist($artifact_suffix)-($version).($ext)"
  } else {
    let cdn = ($env.MILL_GITHUB_RELEASE_CDN? | default ($env.GITHUB_RELEASE_CDN? | default ""))
    $"($cdn)($mill_repo_url)/releases/download/(version-tag $version)/($version)(download-suffix $version)"
  }
}

# Return true when a cached launcher exists and has non-zero size.
def file-nonempty [file: string] {
  if not ($file | path exists) {
    false
  } else {
    let entry = (ls $file | first)
    $entry.size > 0b
  }
}

# Return true when the launcher should print URL/path resolution without downloading.
def dry-run-enabled [] {
  (($env.MILL_TEST_DRY_RUN_LAUNCHER_SCRIPT? | default "") == "1")
}

# Download a Mill launcher to a temporary path, make it executable on Unix, then move it into cache.
def download-mill [url: string, target: string] {
  let output_dir = ($env.MILL_OUTPUT_DIR? | default "out")
  let temp_file = ([$output_dir "mill-temp-download"] | path join)

  mkdir ($temp_file | path dirname)
  print --stderr $"Downloading mill from ($url) ..."

  let result = (curl -f -L -o $temp_file $url | complete)
  if $result.exit_code != 0 {
    print --stderr $result.stderr
    exit $result.exit_code
  }

  if $nu.os-info.family != "windows" {
    chmod +x $temp_file
  }

  mkdir ($target | path dirname)
  mv -f $temp_file $target
}

# Identify Mill options that must remain before `-D mill.main.cli=...`.
def first-arg-is-positional-mill-option [arg: string] {
  ($arg == "--bsp") or ($arg | str starts-with "-i") or ($arg == "--interactive") or ($arg == "--no-server") or ($arg == "--no-daemon") or ($arg == "--help")
}

# Resolve, download if needed, and exec the selected Mill launcher.
def --wrapped main [...args: string] {
  let discovered = (discover-version)
  let raw_version = if $discovered == "" { $default_mill_version } else { $discovered }

  let main_cli = ($env.MILL_MAIN_CLI? | default $script_path)
  let resolved = (resolve-artifact $raw_version)
  let final_download_folder = (
    $env.MILL_FINAL_DOWNLOAD_FOLDER?
    | default ([(default-cache-root) "mill" "download"] | path join)
  )
  let mill = ([$final_download_folder (cache-file-name $raw_version $resolved)] | path join)
  let url = (download-url $resolved.mill_version $resolved.artifact_suffix)

  if (not (file-nonempty $mill)) or (dry-run-enabled) {
    if (dry-run-enabled) {
      print $url
      print $mill
      return
    }

    download-mill $url $mill
  }

  let first_arg = ($args | get 0? | default "")
  let preserve_first = ($first_arg != "") and (first-arg-is-positional-mill-option $first_arg)
  let rest_args = if $preserve_first { $args | skip 1 } else { $args }
  let mill_args = (
    if $preserve_first {
      [$first_arg "-D" $"mill.main.cli=($main_cli)"] ++ $rest_args
    } else {
      ["-D" $"mill.main.cli=($main_cli)"] ++ $rest_args
    }
  )

  exec $mill ...$mill_args
}
