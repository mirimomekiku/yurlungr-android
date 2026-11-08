#!/usr/bin/env python3
# Generates app/src/main/java/dev/yurlungr/app/ui/PhosphorIcons.kt from @phosphor-icons/core.
#   mkdir /tmp/ph && cd /tmp/ph && npm pack @phosphor-icons/core && tar xzf phosphor-icons-core-*.tgz
#   python3 <repo>/tools/phosphor-gen.py > <repo>/app/src/main/java/dev/yurlungr/app/ui/PhosphorIcons.kt
# (run from the directory containing the extracted `package/`). Add icons to ICONS below.
import re, sys, os
BASE = 'package/assets'
# Kotlin name -> (weight, file stem)
ICONS = {
    'List': ('regular', 'list'),
    'Keyboard': ('regular', 'keyboard'),
    'NotePencil': ('regular', 'note-pencil'),
    'DotsThreeVertical': ('bold', 'dots-three-vertical'),
    'Plus': ('regular', 'plus'),
    'ArrowUp': ('bold', 'arrow-up'),
    'Stop': ('fill', 'stop'),
    'X': ('regular', 'x'),
    'CaretLeft': ('regular', 'caret-left'),
    'CaretDown': ('regular', 'caret-down'),
    'CaretRight': ('regular', 'caret-right'),
    'Folder': ('regular', 'folder'),
    'FolderSimple': ('regular', 'folder-simple'),
    'File': ('regular', 'file'),
    'TerminalWindow': ('regular', 'terminal-window'),
    'Sparkle': ('regular', 'sparkle'),
    'Devices': ('regular', 'devices'),
    'GearSix': ('regular', 'gear-six'),
    'DeviceMobile': ('regular', 'device-mobile'),
    'Image': ('regular', 'image'),
    'Paperclip': ('regular', 'paperclip'),
    'CheckCircle': ('fill', 'check-circle'),
    'XCircle': ('fill', 'x-circle'),
    'Circle': ('regular', 'circle'),
    'CircleHalf': ('fill', 'circle-half'),
    'FileText': ('regular', 'file-text'),
    'PencilSimple': ('regular', 'pencil-simple'),
    'Trash': ('regular', 'trash'),
    'ArrowsLeftRight': ('regular', 'arrows-left-right'),
    'MagnifyingGlass': ('regular', 'magnifying-glass'),
    'Terminal': ('regular', 'terminal'),
    'Brain': ('regular', 'brain'),
    'Globe': ('regular', 'globe'),
    'Wrench': ('regular', 'wrench'),
    'GithubLogo': ('regular', 'github-logo'),
    'GitBranch': ('regular', 'git-branch'),
    'DownloadSimple': ('regular', 'download-simple'),
    'Copy': ('regular', 'copy'),
    'SignIn': ('regular', 'sign-in'),
    'AndroidLogo': ('regular', 'android-logo'),
    'BookOpen': ('regular', 'book-open'),
    'Bug': ('regular', 'bug'),
    'TestTube': ('regular', 'test-tube'),
    'ShieldCheck': ('regular', 'shield-check'),
    'ArrowClockwise': ('regular', 'arrow-clockwise'),
    'Hammer': ('regular', 'hammer'),
    'ChatCircle': ('regular', 'chat-circle'),
    'Lightning': ('regular', 'lightning'),
    'ListChecks': ('regular', 'list-checks'),
    'Bell': ('regular', 'bell'),
    'Warning': ('regular', 'warning'),
    'Play': ('regular', 'play'),
    'WifiHigh': ('regular', 'wifi-high'),
    'Info': ('regular', 'info'),
    'GitCommit': ('regular', 'git-commit'),
    'ClockCounterClockwise': ('regular', 'clock-counter-clockwise'),
    'Buildings': ('regular', 'buildings'),
    'User': ('regular', 'user'),
    'FloppyDisk': ('regular', 'floppy-disk'),
    'MagicWand': ('regular', 'magic-wand'),
    'ArrowUUpLeft': ('regular', 'arrow-u-up-left'),
    'ArrowUUpRight': ('regular', 'arrow-u-up-right'),
    'CaretUp': ('regular', 'caret-up'),
    'LockSimple': ('regular', 'lock-simple'),
    'Laptop': ('regular', 'laptop'),
    'DeviceTablet': ('regular', 'device-tablet'),
}
# Icons whose inner chambers must be holes: Compose's nonzero fill closes them, so a phone
# renders as a solid block (bezels filled). Even-odd keeps the outline look.
EVEN_ODD = {'DeviceMobile', 'DeviceTablet', 'Laptop'}
out = []
for name, (weight, stem) in ICONS.items():
    fn = f'{BASE}/{weight}/{stem}' + ('' if weight == 'regular' else f'-{weight}') + '.svg'
    svg = open(fn).read()
    others = set(re.findall(r'<(\w+)', svg)) - {'svg', 'path'}
    if others: sys.exit(f'{fn}: unsupported elements {others}')
    paths = re.findall(r'<path[^>]*\sd="([^"]+)"', svg)
    if not paths: sys.exit(f'{fn}: no path')
    args = ', '.join('"' + p + '"' for p in paths)
    fn_name = 'iconEvenOdd' if name in EVEN_ODD else 'icon'
    out.append(f'    val {name}: ImageVector by lazy {{ {fn_name}("{name}", {args}) }}')
body = '\n'.join(out)
print(f'''package dev.yurlungr.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Phosphor icons (https://phosphoricons.com, MIT, see assets/licenses/Phosphor-LICENSE.txt),
 * vendored as path data from @phosphor-icons/core rather than pulling in all ~9,000 vectors.
 * Regular weight unless named otherwise in the generator (fill for status glyphs, bold for
 * small action icons). Generated: add icons by re-running the generator, not by hand.
 */
object Ph {{
{body}

    private fun icon(name: String, vararg paths: String): ImageVector = build(name, PathFillType.NonZero, paths)

    private fun iconEvenOdd(name: String, vararg paths: String): ImageVector = build(name, PathFillType.EvenOdd, paths)

    private fun build(name: String, fillType: PathFillType, paths: Array<out String>): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 256f, viewportHeight = 256f)
            .apply {{ for (d in paths) addPath(pathData = addPathNodes(d), pathFillType = fillType, fill = SolidColor(Color.Black)) }}
            .build()
}}''')
