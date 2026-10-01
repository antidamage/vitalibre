#!/usr/bin/env python3
"""Generate VitaLibre.xcodeproj.

There is no xcodegen on the build host, and a hand-edited pbxproj is fragile,
so the project is generated. It uses Xcode 16+ synchronised folders: every
file under App/, Core/ and publisher/ belongs to the app target automatically
(sources compile, everything else is bundled), so adding a file never means
touching the project.

    python tools/gen_xcodeproj.py            # writes VitaLibre.xcodeproj
    python tools/gen_xcodeproj.py --team X   # override the development team
"""
import argparse
import hashlib
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def oid(name: str) -> str:
    return hashlib.md5(("vitals-libre:" + name).encode()).hexdigest()[:24].upper()


def build_settings(d: dict, indent: int) -> str:
    pad = "\t" * indent
    lines = []
    for k in sorted(d):
        v = d[k]
        if isinstance(v, bool):
            v = "YES" if v else "NO"
        v = str(v)
        if not v or any(c in v for c in ' "$()<>,;=@') and not (v.startswith('"') and v.endswith('"')):
            v = '"' + v.replace('"', '\\"') + '"'
        lines.append(f"{pad}{k} = {v};")
    return "\n".join(lines)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--team", default=os.environ.get("VITALIBRE_TEAM", ""),
                    help="development team id; leave empty so none is written into the project (builds pass it on the command line)")
    ap.add_argument("--bundle-id", default="nz.skull.vitalibre.claude")
    args = ap.parse_args()

    has_icon = os.path.isdir(os.path.join(ROOT, "App", "Assets.xcassets", "AppIcon.appiconset"))

    project_common = {
        "ALWAYS_SEARCH_USER_PATHS": "NO",
        "CLANG_ENABLE_MODULES": "YES",
        "CLANG_ENABLE_OBJC_ARC": "YES",
        "COPY_PHASE_STRIP": "NO",
        "ENABLE_STRICT_OBJC_MSGSEND": "YES",
        "GCC_NO_COMMON_BLOCKS": "YES",
        "IPHONEOS_DEPLOYMENT_TARGET": "17.0",
        "MTL_FAST_MATH": "YES",
        "SDKROOT": "iphoneos",
        "SWIFT_VERSION": "5.0",
    }
    project_debug = dict(project_common, **{
        "DEBUG_INFORMATION_FORMAT": "dwarf",
        "ENABLE_TESTABILITY": "YES",
        "GCC_OPTIMIZATION_LEVEL": "0",
        "GCC_PREPROCESSOR_DEFINITIONS": '"DEBUG=1 $(inherited)"',
        "MTL_ENABLE_DEBUG_INFO": "INCLUDE_SOURCE",
        "ONLY_ACTIVE_ARCH": "YES",
        "SWIFT_ACTIVE_COMPILATION_CONDITIONS": "DEBUG",
        "SWIFT_OPTIMIZATION_LEVEL": '"-Onone"',
    })
    project_release = dict(project_common, **{
        "DEBUG_INFORMATION_FORMAT": '"dwarf-with-dsym"',
        "ENABLE_NS_ASSERTIONS": "NO",
        "MTL_ENABLE_DEBUG_INFO": "NO",
        "SWIFT_COMPILATION_MODE": "wholemodule",
        "VALIDATE_PRODUCT": "YES",
    })
    target = {
        "ASSETCATALOG_COMPILER_APPICON_NAME": "AppIcon" if has_icon else "",
        "CODE_SIGN_STYLE": "Automatic",
        "CURRENT_PROJECT_VERSION": "1",
        "DEVELOPMENT_TEAM": args.team,
        "ENABLE_PREVIEWS": "YES",
        "GENERATE_INFOPLIST_FILE": "YES",
        "INFOPLIST_KEY_CFBundleDisplayName": '"VitaLibre"',
        "INFOPLIST_KEY_NSCameraUsageDescription": '"VitaLibre uses the camera and flash to see the pulse in your fingertip. The images are processed on this device and never stored or sent anywhere."',
        "INFOPLIST_KEY_UIApplicationSceneManifest_Generation": "YES",
        "INFOPLIST_KEY_UIApplicationSupportsIndirectInputEvents": "YES",
        "INFOPLIST_KEY_UILaunchScreen_Generation": "YES",
        "INFOPLIST_KEY_UISupportedInterfaceOrientations_iPhone": "UIInterfaceOrientationPortrait",
        "LD_RUNPATH_SEARCH_PATHS": '"$(inherited) @executable_path/Frameworks"',
        "MARKETING_VERSION": "0.1.0",
        "PRODUCT_BUNDLE_IDENTIFIER": args.bundle_id,
        "PRODUCT_NAME": '"$(TARGET_NAME)"',
        "SUPPORTED_PLATFORMS": '"iphoneos iphonesimulator"',
        "SWIFT_EMIT_LOC_STRINGS": "YES",
        "TARGETED_DEVICE_FAMILY": "1",
    }
    target = {k: v for k, v in target.items() if v != ""}  # an empty team is simply not written
    target_debug, target_release = dict(target), dict(target)

    ids = {n: oid(n) for n in [
        "project", "target", "main", "products", "app", "src", "fw", "res",
        "grp.App", "grp.Core", "grp.publisher", "cfg.project", "cfg.target",
        "p.debug", "p.release", "t.debug", "t.release",
    ]}
    groups = ("App", "Core", "publisher")

    def fmt(d: dict, indent: int) -> str:
        return "\n".join(
            "\t" * indent + f"{k} = {v if isinstance(v, str) and (v.startswith(chr(34)) or v.replace('_','').replace('.','').replace('-','').isalnum()) else chr(34) + str(v) + chr(34)};"
            for k, v in sorted(d.items()))

    out = f"""// !$*UTF8*$!
{{
	archiveVersion = 1;
	classes = {{
	}};
	objectVersion = 77;
	objects = {{

/* Begin PBXFileReference section */
		{ids['app']} /* VitaLibre.app */ = {{isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = VitaLibre.app; sourceTree = BUILT_PRODUCTS_DIR; }};
/* End PBXFileReference section */

/* Begin PBXFileSystemSynchronizedRootGroup section */
""" + "".join(
        f"\t\t{ids['grp.' + g]} /* {g} */ = {{isa = PBXFileSystemSynchronizedRootGroup; path = {g}; sourceTree = \"<group>\"; }};\n"
        for g in groups) + f"""/* End PBXFileSystemSynchronizedRootGroup section */

/* Begin PBXFrameworksBuildPhase section */
		{ids['fw']} /* Frameworks */ = {{
			isa = PBXFrameworksBuildPhase;
			buildActionMask = 2147483647;
			files = (
			);
			runOnlyForDeploymentPostprocessing = 0;
		}};
/* End PBXFrameworksBuildPhase section */

/* Begin PBXGroup section */
		{ids['main']} = {{
			isa = PBXGroup;
			children = (
""" + "".join(f"\t\t\t\t{ids['grp.' + g]} /* {g} */,\n" for g in groups) + f"""				{ids['products']} /* Products */,
			);
			sourceTree = "<group>";
		}};
		{ids['products']} /* Products */ = {{
			isa = PBXGroup;
			children = (
				{ids['app']} /* VitaLibre.app */,
			);
			name = Products;
			sourceTree = "<group>";
		}};
/* End PBXGroup section */

/* Begin PBXNativeTarget section */
		{ids['target']} /* VitaLibre */ = {{
			isa = PBXNativeTarget;
			buildConfigurationList = {ids['cfg.target']} /* Build configuration list for PBXNativeTarget "VitaLibre" */;
			buildPhases = (
				{ids['src']} /* Sources */,
				{ids['fw']} /* Frameworks */,
				{ids['res']} /* Resources */,
			);
			buildRules = (
			);
			dependencies = (
			);
			fileSystemSynchronizedGroups = (
""" + "".join(f"\t\t\t\t{ids['grp.' + g]} /* {g} */,\n" for g in groups) + f"""			);
			name = VitaLibre;
			packageProductDependencies = (
			);
			productName = VitaLibre;
			productReference = {ids['app']} /* VitaLibre.app */;
			productType = "com.apple.product-type.application";
		}};
/* End PBXNativeTarget section */

/* Begin PBXProject section */
		{ids['project']} /* Project object */ = {{
			isa = PBXProject;
			attributes = {{
				BuildIndependentTargetsInParallel = 1;
				LastSwiftUpdateCheck = 2630;
				LastUpgradeCheck = 2630;
				TargetAttributes = {{
					{ids['target']} = {{
						CreatedOnToolsVersion = 26.3;
					}};
				}};
			}};
			buildConfigurationList = {ids['cfg.project']} /* Build configuration list for PBXProject "VitaLibre" */;
			developmentRegion = en;
			hasScannedForEncodings = 0;
			knownRegions = (
				en,
				Base,
			);
			mainGroup = {ids['main']};
			minimizedProjectReferenceProperties = (
			);
			productRefGroup = {ids['products']} /* Products */;
			projectDirPath = "";
			projectRoot = "";
			targets = (
				{ids['target']} /* VitaLibre */,
			);
		}};
/* End PBXProject section */

/* Begin PBXResourcesBuildPhase section */
		{ids['res']} /* Resources */ = {{
			isa = PBXResourcesBuildPhase;
			buildActionMask = 2147483647;
			files = (
			);
			runOnlyForDeploymentPostprocessing = 0;
		}};
/* End PBXResourcesBuildPhase section */

/* Begin PBXSourcesBuildPhase section */
		{ids['src']} /* Sources */ = {{
			isa = PBXSourcesBuildPhase;
			buildActionMask = 2147483647;
			files = (
			);
			runOnlyForDeploymentPostprocessing = 0;
		}};
/* End PBXSourcesBuildPhase section */

/* Begin XCBuildConfiguration section */
		{ids['p.debug']} /* Debug */ = {{
			isa = XCBuildConfiguration;
			buildSettings = {{
{fmt(project_debug, 4)}
			}};
			name = Debug;
		}};
		{ids['p.release']} /* Release */ = {{
			isa = XCBuildConfiguration;
			buildSettings = {{
{fmt(project_release, 4)}
			}};
			name = Release;
		}};
		{ids['t.debug']} /* Debug */ = {{
			isa = XCBuildConfiguration;
			buildSettings = {{
{fmt(target_debug, 4)}
			}};
			name = Debug;
		}};
		{ids['t.release']} /* Release */ = {{
			isa = XCBuildConfiguration;
			buildSettings = {{
{fmt(target_release, 4)}
			}};
			name = Release;
		}};
/* End XCBuildConfiguration section */

/* Begin XCConfigurationList section */
		{ids['cfg.project']} /* Build configuration list for PBXProject "VitaLibre" */ = {{
			isa = XCConfigurationList;
			buildConfigurations = (
				{ids['p.debug']} /* Debug */,
				{ids['p.release']} /* Release */,
			);
			defaultConfigurationIsVisible = 0;
			defaultConfigurationName = Release;
		}};
		{ids['cfg.target']} /* Build configuration list for PBXNativeTarget "VitaLibre" */ = {{
			isa = XCConfigurationList;
			buildConfigurations = (
				{ids['t.debug']} /* Debug */,
				{ids['t.release']} /* Release */,
			);
			defaultConfigurationIsVisible = 0;
			defaultConfigurationName = Release;
		}};
/* End XCConfigurationList section */
	}};
	rootObject = {ids['project']} /* Project object */;
}}
"""
    proj = os.path.join(ROOT, "VitaLibre.xcodeproj")
    os.makedirs(os.path.join(proj, "xcshareddata", "xcschemes"), exist_ok=True)
    with open(os.path.join(proj, "project.pbxproj"), "w", newline="\n") as f:
        f.write(out)

    scheme = f"""<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion = "2630" version = "1.7">
   <BuildAction parallelizeBuildables = "YES" buildImplicitDependencies = "YES">
      <BuildActionEntries>
         <BuildActionEntry buildForTesting = "YES" buildForRunning = "YES" buildForProfiling = "YES" buildForArchiving = "YES" buildForAnalyzing = "YES">
            <BuildableReference BuildableIdentifier = "primary" BlueprintIdentifier = "{ids['target']}" BuildableName = "VitaLibre.app" BlueprintName = "VitaLibre" ReferencedContainer = "container:VitaLibre.xcodeproj">
            </BuildableReference>
         </BuildActionEntry>
      </BuildActionEntries>
   </BuildAction>
   <TestAction buildConfiguration = "Debug" selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv = "YES">
   </TestAction>
   <LaunchAction buildConfiguration = "Debug" selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB" launchStyle = "0" useCustomWorkingDirectory = "NO" ignoresPersistentStateOnLaunch = "NO" debugDocumentVersioning = "YES" debugServiceExtension = "internal" allowLocationSimulation = "YES">
      <BuildableProductRunnable runnableDebuggingMode = "0">
         <BuildableReference BuildableIdentifier = "primary" BlueprintIdentifier = "{ids['target']}" BuildableName = "VitaLibre.app" BlueprintName = "VitaLibre" ReferencedContainer = "container:VitaLibre.xcodeproj">
         </BuildableReference>
      </BuildableProductRunnable>
   </LaunchAction>
   <ProfileAction buildConfiguration = "Release" shouldUseLaunchSchemeArgsEnv = "YES" savedToolIdentifier = "" useCustomWorkingDirectory = "NO" debugDocumentVersioning = "YES">
   </ProfileAction>
   <AnalyzeAction buildConfiguration = "Debug">
   </AnalyzeAction>
   <ArchiveAction buildConfiguration = "Release" revealArchiveInOrganizer = "YES">
   </ArchiveAction>
</Scheme>
"""
    with open(os.path.join(proj, "xcshareddata", "xcschemes", "VitaLibre.xcscheme"), "w", newline="\n") as f:
        f.write(scheme)
    print("wrote", proj)


if __name__ == "__main__":
    main()
