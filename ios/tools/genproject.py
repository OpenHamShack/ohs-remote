#!/usr/bin/env python3
"""Write ios/OpenHamShackRemote.xcodeproj from this file, the same every time.

    python3 ios/tools/genproject.py

The project is generated rather than kept by Xcode because no project tool
(XcodeGen, Tuist) is installed on the build Mac and none can be, and because
a project.pbxproj edited by hand through Xcode's GUI turns every change into
an unreadable diff of random IDs. This script is the project: two app
targets, two local Swift packages, settings below. Object IDs are hashes of
stable names, so running it again changes nothing unless this file did.

It uses Xcode 16's file-system-synchronised folders: App/, TV/ and Shared/
are compiled as whatever .swift files and asset catalogues they hold, so
adding or renaming a source file needs no regeneration - only a new target,
package or setting does. Info.plists live in Config/, outside those
folders, so they are not swept up as resources; they are written by this
script too (PLISTS), so the whole of the apps' configuration is here.

Strings (docs/I18N.md): both apps share one String Catalog,
Shared/Localizable.xcstrings. Being in Shared/ is what puts it in both
targets - the synchronised folder compiles it as a resource like any asset
catalogue - so nothing here names it. What this file does set is the rest
of the localisation contract: developmentRegion en with en in knownRegions
(English is the source language), LOCALIZATION_PREFERS_STRING_CATALOGS and
SWIFT_EMIT_LOC_STRINGS, without which the compiler extracts nothing.
tools/sync-strings.sh merges what it extracted into the catalog.

Commit the generated project with the change to this file.
"""

from __future__ import annotations

import hashlib
import plistlib
from pathlib import Path

IOS = Path(__file__).resolve().parent.parent
PROJ = IOS / "OpenHamShackRemote.xcodeproj"

BUNDLE_ID = "com.openhamshack.remote"
MARKETING_VERSION = "0.1.0"
BUILD = "1"

# name -> (platform settings, synchronised folders, package products, plist)
TARGETS = {
    "OpenHamShackRemote": dict(
        display="OpenHamShack Remote",
        folders=["App", "Shared"],
        products=["OpenHamShackKit", "OpenHamShackPlayer", "OpenHamShackCamera"],
        settings={
            "SDKROOT": "iphoneos",
            "SUPPORTED_PLATFORMS": "iphoneos iphonesimulator",
            "TARGETED_DEVICE_FAMILY": "1,2",
            "IPHONEOS_DEPLOYMENT_TARGET": "17.0",
            "INFOPLIST_FILE": "Config/iPad-Info.plist",
            "INFOPLIST_KEY_UILaunchScreen_Generation": "YES",
            "INFOPLIST_KEY_UIApplicationSceneManifest_Generation": "YES",
            "INFOPLIST_KEY_UIApplicationSupportsIndirectInputEvents": "YES",
            "INFOPLIST_KEY_UISupportedInterfaceOrientations_iPad":
                "UIInterfaceOrientationPortrait UIInterfaceOrientationPortraitUpsideDown "
                "UIInterfaceOrientationLandscapeLeft UIInterfaceOrientationLandscapeRight",
            "INFOPLIST_KEY_UISupportedInterfaceOrientations_iPhone":
                "UIInterfaceOrientationPortrait UIInterfaceOrientationLandscapeLeft UIInterfaceOrientationLandscapeRight",
            "INFOPLIST_KEY_UIStatusBarHidden": "NO",
        },
    ),
    "OpenHamShackRemoteTV": dict(
        display="OpenHamShack",
        folders=["TV", "Shared"],
        products=["OpenHamShackKit", "OpenHamShackPlayer"],
        settings={
            "SDKROOT": "appletvos",
            "SUPPORTED_PLATFORMS": "appletvos appletvsimulator",
            "TARGETED_DEVICE_FAMILY": "3",
            "TVOS_DEPLOYMENT_TARGET": "17.0",
            "INFOPLIST_FILE": "Config/TV-Info.plist",
            "INFOPLIST_KEY_UILaunchScreen_Generation": "YES",
            "INFOPLIST_KEY_UIApplicationSceneManifest_Generation": "YES",
            "INFOPLIST_KEY_UIUserInterfaceStyle": "Dark",
        },
    ),
}
# The Info.plist keys Xcode cannot generate from build settings, written
# to Config/ here and merged with the generated Info.plist at build time.
#
# App Transport Security: the box serves plain HTTP with no certificate, on
# a LAN address, a .local name, or a tailnet address (100.64.0.0/10) that
# NSAllowsLocalNetworking does not cover - so arbitrary loads, and that key
# alone: with NSAllowsLocalNetworking beside it iOS ignores it, and the
# tailnet address was refused (error -1022 in the simulator). Every URL the
# app opens is built from the box address the operator chose.
#
# Bonjour: the box advertises _openhamshack._tcp (REMOTE-API.md 1). iOS and
# tvOS browse only the types listed, and only after asking with the usage
# string.
_COMMON_PLIST = {
    "NSAppTransportSecurity": {"NSAllowsArbitraryLoads": True},
    "NSBonjourServices": ["_openhamshack._tcp"],
}
PLISTS = {
    "iPad-Info.plist": {
        **_COMMON_PLIST,
        "NSLocalNetworkUsageDescription": "OpenHamShack Remote finds and talks to your OpenHamShack on this network.",
        "NSCameraUsageDescription": "To send this device's camera to your OpenHamShack as its picture, only while you choose to.",
        "NSMicrophoneUsageDescription": "To send sound with the camera picture to your OpenHamShack, only while you choose to.",
    },
    "TV-Info.plist": {
        **_COMMON_PLIST,
        "NSLocalNetworkUsageDescription": "OpenHamShack finds the OpenHamShack boxes on this network and shows their pictures.",
    },
}

PACKAGES = ["OpenHamShackKit", "OpenHamShackMedia"]
PRODUCT_PACKAGE = {"OpenHamShackKit": "OpenHamShackKit", "OpenHamShackPlayer": "OpenHamShackMedia", "OpenHamShackCamera": "OpenHamShackMedia"}

COMMON_TARGET = {
    "ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME": "AccentColor",
    "CODE_SIGN_STYLE": "Automatic",
    # Empty here; a device build passes DEVELOPMENT_TEAM=<team> on the
    # command line (or sets it once in Xcode's Signing tab, unsaved).
    "DEVELOPMENT_TEAM": "",
    "CURRENT_PROJECT_VERSION": BUILD,
    "MARKETING_VERSION": MARKETING_VERSION,
    "GENERATE_INFOPLIST_FILE": "YES",
    "LD_RUNPATH_SEARCH_PATHS": "$(inherited) @executable_path/Frameworks",
    "PRODUCT_BUNDLE_IDENTIFIER": BUNDLE_ID,
    "ENABLE_PREVIEWS": "YES",
    # Swift 5 language mode: VLCKit's and HaishinKit's delegate callbacks
    # are not annotated for Swift 6's strict concurrency.
    "SWIFT_VERSION": "5.0",
    "SWIFT_EMIT_LOC_STRINGS": "YES",
}

PROJECT_COMMON = {
    "ALWAYS_SEARCH_USER_PATHS": "NO",
    "CLANG_ENABLE_MODULES": "YES",
    "CLANG_ENABLE_OBJC_ARC": "YES",
    "ENABLE_STRICT_OBJC_MSGSEND": "YES",
    "ENABLE_USER_SCRIPT_SANDBOXING": "YES",
    "GCC_C_LANGUAGE_STANDARD": "gnu17",
    "LOCALIZATION_PREFERS_STRING_CATALOGS": "YES",
    "SWIFT_VERSION": "5.0",
    # Simulators are arm64 only: VLCKit is thinned to arm64 (fetch-vlckit.sh)
    # and HaishinKit's libsrt ships no x86_64 simulator slice either. On an
    # Intel Mac, KEEP_X86=1 for VLCKit would not be enough on its own.
    "EXCLUDED_ARCHS[sdk=iphonesimulator*]": "x86_64",
    "EXCLUDED_ARCHS[sdk=appletvsimulator*]": "x86_64",
}
PROJECT_DEBUG = {
    "DEBUG_INFORMATION_FORMAT": "dwarf",
    "ENABLE_TESTABILITY": "YES",
    "GCC_OPTIMIZATION_LEVEL": "0",
    "ONLY_ACTIVE_ARCH": "YES",
    "SWIFT_ACTIVE_COMPILATION_CONDITIONS": "DEBUG $(inherited)",
    "SWIFT_OPTIMIZATION_LEVEL": "-Onone",
}
PROJECT_RELEASE = {
    "DEBUG_INFORMATION_FORMAT": "dwarf-with-dsym",
    "ENABLE_NS_ASSERTIONS": "NO",
    "SWIFT_COMPILATION_MODE": "wholemodule",
    "VALIDATE_PRODUCT": "YES",
}


def oid(*parts: str) -> str:
    """A stable 24-hex-digit object ID for a name."""
    return hashlib.sha256("/".join(parts).encode()).hexdigest()[:24].upper()


def q(v: str) -> str:
    """A pbxproj string: bare when it is plainly safe, quoted otherwise."""
    if v and all(c.isalnum() or c in "._/" for c in v) and not v[0].isdigit():
        return v
    return '"' + v.replace("\\", "\\\\").replace('"', '\\"') + '"'


def settings_block(d: dict, indent: str) -> str:
    out = []
    for k in sorted(d):
        v = d[k]
        if isinstance(v, list):
            items = "".join(f"{indent}\t\t{q(x)},\n" for x in v)
            out.append(f"{indent}\t{q(k)} = (\n{items}{indent}\t);\n")
        else:
            out.append(f"{indent}\t{q(k)} = {q(str(v))};\n")
    return "".join(out)


def build() -> tuple[str, dict[str, str]]:
    objs: dict[str, dict[str, str]] = {}   # section -> id -> body

    def add(section: str, ident: str, comment: str, body: str) -> None:
        objs.setdefault(section, {})[f"{ident} /* {comment} */"] = body

    project = oid("project")
    main_group = oid("group", "main")
    products_group = oid("group", "Products")
    folders = sorted({f for t in TARGETS.values() for f in t["folders"]})
    folder_ids = {f: oid("folder", f) for f in folders}
    config_group = oid("group", "Config")
    package_refs = {p: oid("package", p) for p in PACKAGES}

    for f in folders:
        add("PBXFileSystemSynchronizedRootGroup", folder_ids[f], f,
            f"{{\n\t\t\tisa = PBXFileSystemSynchronizedRootGroup;\n\t\t\tpath = {f};\n\t\t\tsourceTree = \"<group>\";\n\t\t}}")
    for p in PACKAGES:
        add("XCLocalSwiftPackageReference", package_refs[p], f'XCLocalSwiftPackageReference "{p}"',
            f"{{\n\t\t\tisa = XCLocalSwiftPackageReference;\n\t\t\trelativePath = {p};\n\t\t}}")

    plist_refs = {}
    for name, t in TARGETS.items():
        plist = Path(t["settings"]["INFOPLIST_FILE"]).name
        plist_refs[plist] = oid("file", plist)
        add("PBXFileReference", plist_refs[plist], plist,
            f"{{isa = PBXFileReference; lastKnownFileType = text.plist.xml; path = {q(plist)}; sourceTree = \"<group>\"; }}")

    target_ids = {}
    product_refs = {}
    for name, t in TARGETS.items():
        tid = target_ids[name] = oid("target", name)
        product_refs[name] = oid("product", name)
        add("PBXFileReference", product_refs[name], f"{name}.app",
            f"{{isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; "
            f"path = {name}.app; sourceTree = BUILT_PRODUCTS_DIR; }}")
        sources, frameworks, resources = oid(name, "sources"), oid(name, "frameworks"), oid(name, "resources")
        deps = []
        fw_files = []
        for prod in t["products"]:
            dep = oid(name, "dep", prod)
            deps.append(f"\t\t\t\t{dep} /* {prod} */,\n")
            add("XCSwiftPackageProductDependency", dep, prod,
                f"{{\n\t\t\tisa = XCSwiftPackageProductDependency;\n\t\t\tproductName = {prod};\n\t\t}}")
            bf = oid(name, "buildfile", prod)
            fw_files.append(f"\t\t\t\t{bf} /* {prod} in Frameworks */,\n")
            add("PBXBuildFile", bf, f"{prod} in Frameworks", f"{{isa = PBXBuildFile; productRef = {dep} /* {prod} */; }}")
        add("PBXSourcesBuildPhase", sources, "Sources",
            "{\n\t\t\tisa = PBXSourcesBuildPhase;\n\t\t\tbuildActionMask = 2147483647;\n\t\t\tfiles = (\n\t\t\t);\n"
            "\t\t\trunOnlyForDeploymentPostprocessing = 0;\n\t\t}")
        add("PBXFrameworksBuildPhase", frameworks, "Frameworks",
            "{\n\t\t\tisa = PBXFrameworksBuildPhase;\n\t\t\tbuildActionMask = 2147483647;\n\t\t\tfiles = (\n"
            + "".join(fw_files) + "\t\t\t);\n\t\t\trunOnlyForDeploymentPostprocessing = 0;\n\t\t}")
        add("PBXResourcesBuildPhase", resources, "Resources",
            "{\n\t\t\tisa = PBXResourcesBuildPhase;\n\t\t\tbuildActionMask = 2147483647;\n\t\t\tfiles = (\n\t\t\t);\n"
            "\t\t\trunOnlyForDeploymentPostprocessing = 0;\n\t\t}")
        cfg_list = oid(name, "configlist")
        cfgs = []
        for cfg in ("Debug", "Release"):
            cid = oid(name, "config", cfg)
            cfgs.append(f"\t\t\t\t{cid} /* {cfg} */,\n")
            s = {**COMMON_TARGET, **t["settings"], "PRODUCT_NAME": "$(TARGET_NAME)",
                 "INFOPLIST_KEY_CFBundleDisplayName": t["display"]}
            add("XCBuildConfiguration", cid, cfg,
                "{\n\t\t\tisa = XCBuildConfiguration;\n\t\t\tbuildSettings = {\n" + settings_block(s, "\t\t\t")
                + f"\t\t\t}};\n\t\t\tname = {cfg};\n\t\t}}")
        add("XCConfigurationList", cfg_list, f'Build configuration list for PBXNativeTarget "{name}"',
            "{\n\t\t\tisa = XCConfigurationList;\n\t\t\tbuildConfigurations = (\n" + "".join(cfgs)
            + "\t\t\t);\n\t\t\tdefaultConfigurationIsVisible = 0;\n\t\t\tdefaultConfigurationName = Release;\n\t\t}")
        groups = "".join(f"\t\t\t\t{folder_ids[f]} /* {f} */,\n" for f in t["folders"])
        add("PBXNativeTarget", tid, name,
            "{\n\t\t\tisa = PBXNativeTarget;\n"
            f"\t\t\tbuildConfigurationList = {cfg_list} /* Build configuration list for PBXNativeTarget \"{name}\" */;\n"
            "\t\t\tbuildPhases = (\n"
            f"\t\t\t\t{sources} /* Sources */,\n\t\t\t\t{frameworks} /* Frameworks */,\n\t\t\t\t{resources} /* Resources */,\n"
            "\t\t\t);\n\t\t\tbuildRules = (\n\t\t\t);\n\t\t\tdependencies = (\n\t\t\t);\n"
            "\t\t\tfileSystemSynchronizedGroups = (\n" + groups + "\t\t\t);\n"
            f"\t\t\tname = {name};\n"
            "\t\t\tpackageProductDependencies = (\n" + "".join(deps) + "\t\t\t);\n"
            f"\t\t\tproductName = {name};\n"
            f"\t\t\tproductReference = {product_refs[name]} /* {name}.app */;\n"
            "\t\t\tproductType = \"com.apple.product-type.application\";\n\t\t}")

    # Groups
    children = "".join(f"\t\t\t\t{folder_ids[f]} /* {f} */,\n" for f in folders)
    children += f"\t\t\t\t{config_group} /* Config */,\n\t\t\t\t{products_group} /* Products */,\n"
    add("PBXGroup", main_group, "main",
        "{\n\t\t\tisa = PBXGroup;\n\t\t\tchildren = (\n" + children + "\t\t\t);\n\t\t\tsourceTree = \"<group>\";\n\t\t}")
    add("PBXGroup", config_group, "Config",
        "{\n\t\t\tisa = PBXGroup;\n\t\t\tchildren = (\n"
        + "".join(f"\t\t\t\t{i} /* {n} */,\n" for n, i in sorted(plist_refs.items()))
        + "\t\t\t);\n\t\t\tpath = Config;\n\t\t\tsourceTree = \"<group>\";\n\t\t}")
    add("PBXGroup", products_group, "Products",
        "{\n\t\t\tisa = PBXGroup;\n\t\t\tchildren = (\n"
        + "".join(f"\t\t\t\t{product_refs[n]} /* {n}.app */,\n" for n in TARGETS)
        + "\t\t\t);\n\t\t\tname = Products;\n\t\t\tsourceTree = \"<group>\";\n\t\t}")

    # Project
    plist_cfgs = []
    for cfg, extra in (("Debug", PROJECT_DEBUG), ("Release", PROJECT_RELEASE)):
        cid = oid("project", "config", cfg)
        plist_cfgs.append(f"\t\t\t\t{cid} /* {cfg} */,\n")
        add("XCBuildConfiguration", cid, cfg,
            "{\n\t\t\tisa = XCBuildConfiguration;\n\t\t\tbuildSettings = {\n"
            + settings_block({**PROJECT_COMMON, **extra}, "\t\t\t") + f"\t\t\t}};\n\t\t\tname = {cfg};\n\t\t}}")
    proj_cfg_list = oid("project", "configlist")
    add("XCConfigurationList", proj_cfg_list, 'Build configuration list for PBXProject "OpenHamShackRemote"',
        "{\n\t\t\tisa = XCConfigurationList;\n\t\t\tbuildConfigurations = (\n" + "".join(plist_cfgs)
        + "\t\t\t);\n\t\t\tdefaultConfigurationIsVisible = 0;\n\t\t\tdefaultConfigurationName = Release;\n\t\t}")
    attrs = "".join(f"\t\t\t\t\t{target_ids[n]} = {{\n\t\t\t\t\t\tCreatedOnToolsVersion = 26.0;\n\t\t\t\t\t}};\n"
                    for n in TARGETS)
    add("PBXProject", project, "Project object",
        "{\n\t\t\tisa = PBXProject;\n\t\t\tattributes = {\n\t\t\t\tBuildIndependentTargetsInParallel = 1;\n"
        "\t\t\t\tLastSwiftUpdateCheck = 2600;\n\t\t\t\tLastUpgradeCheck = 2600;\n\t\t\t\tTargetAttributes = {\n" + attrs
        + "\t\t\t\t};\n\t\t\t};\n"
        f"\t\t\tbuildConfigurationList = {proj_cfg_list} /* Build configuration list for PBXProject \"OpenHamShackRemote\" */;\n"
        "\t\t\tdevelopmentRegion = en;\n\t\t\thasScannedForEncodings = 0;\n\t\t\tknownRegions = (\n\t\t\t\ten,\n\t\t\t\tBase,\n\t\t\t);\n"
        f"\t\t\tmainGroup = {main_group};\n\t\t\tminimizedProjectReferenceProxies = 1;\n"
        "\t\t\tpackageReferences = (\n"
        + "".join(f"\t\t\t\t{package_refs[p]} /* XCLocalSwiftPackageReference \"{p}\" */,\n" for p in PACKAGES)
        + f"\t\t\t);\n\t\t\tpreferredProjectObjectVersion = 77;\n\t\t\tproductRefGroup = {products_group} /* Products */;\n"
        "\t\t\tprojectDirPath = \"\";\n\t\t\tprojectRoot = \"\";\n\t\t\ttargets = (\n"
        + "".join(f"\t\t\t\t{target_ids[n]} /* {n} */,\n" for n in TARGETS) + "\t\t\t);\n\t\t}")

    out = ["// !$*UTF8*$!\n{\n\tarchiveVersion = 1;\n\tclasses = {\n\t};\n\tobjectVersion = 77;\n\tobjects = {\n"]
    for section in sorted(objs):
        out.append(f"\n/* Begin {section} section */\n")
        for key in sorted(objs[section]):
            body = objs[section][key]
            out.append(f"\t\t{key} = {body};\n")
        out.append(f"/* End {section} section */\n")
    out.append(f"\t}};\n\trootObject = {project} /* Project object */;\n}}\n")
    return "".join(out), target_ids


def scheme(name: str, tid: str) -> str:
    ref = (f'<BuildableReference BuildableIdentifier = "primary" BlueprintIdentifier = "{tid}" '
           f'BuildableName = "{name}.app" BlueprintName = "{name}" ReferencedContainer = "container:OpenHamShackRemote.xcodeproj">'
           "</BuildableReference>")
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion = "2600" version = "1.7">
   <BuildAction parallelizeBuildables = "YES" buildImplicitDependencies = "YES">
      <BuildActionEntries>
         <BuildActionEntry buildForTesting = "YES" buildForRunning = "YES" buildForProfiling = "YES" buildForArchiving = "YES" buildForAnalyzing = "YES">
            {ref}
         </BuildActionEntry>
      </BuildActionEntries>
   </BuildAction>
   <TestAction buildConfiguration = "Debug" selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv = "YES">
   </TestAction>
   <LaunchAction buildConfiguration = "Debug" selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB" launchStyle = "0" useCustomWorkingDirectory = "NO" ignoresPersistentStateOnLaunch = "NO" debugDocumentVersioning = "YES" debugServiceExtension = "internal" allowLocationSimulation = "YES">
      <BuildableProductRunnable runnableDebuggingMode = "0">
         {ref}
      </BuildableProductRunnable>
   </LaunchAction>
   <ProfileAction buildConfiguration = "Release" shouldUseLaunchSchemeArgsEnv = "YES" savedToolIdentifier = "" useCustomWorkingDirectory = "NO" debugDocumentVersioning = "YES">
      <BuildableProductRunnable runnableDebuggingMode = "0">
         {ref}
      </BuildableProductRunnable>
   </ProfileAction>
   <AnalyzeAction buildConfiguration = "Debug">
   </AnalyzeAction>
   <ArchiveAction buildConfiguration = "Release" revealArchiveInOrganizer = "YES">
   </ArchiveAction>
</Scheme>
"""


def main() -> None:
    for name, keys in PLISTS.items():
        (IOS / "Config" / name).write_bytes(plistlib.dumps(keys, sort_keys=True))
    text, target_ids = build()
    (PROJ / "xcshareddata" / "xcschemes").mkdir(parents=True, exist_ok=True)
    (PROJ / "project.pbxproj").write_text(text)
    for name, tid in target_ids.items():
        (PROJ / "xcshareddata" / "xcschemes" / f"{name}.xcscheme").write_text(scheme(name, tid))
    print(f"wrote {PROJ.relative_to(IOS.parent)}")


if __name__ == "__main__":
    main()
