package ru.workinprogress.kachok.ui.icons

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * The design's glyphs, drawn the way the design draws them: Material Symbols Rounded at weight 400,
 * fill 0, optical size 20.
 *
 * A font rather than vector assets, because that is what the design used and the shapes have to be
 * the same ones — a lookalike from another icon set is a difference nobody catches in review and
 * everybody sees on screen.
 *
 * **By codepoint, not by ligature name.** Material Symbols reaches a glyph either way, and the
 * ligature route cannot be subset: the substitution table maps letters to every icon in the font,
 * so keeping the feature keeps all four thousand of them. Cutting by codepoint leaves the
 * twenty-one this UI draws.
 *
 * `scripts/subset_icon_font.sh` reads the codepoints out of this file, so adding one here without
 * re-running it gives a missing glyph — visible — rather than a wrong one.
 */
public object Icons {
    public const val LINK: String = "\ue250" // link
    public const val RULE: String = "\uf1c2" // rule
    public const val ARROW_DOWNWARD: String = "\ue5db" // arrow_downward
    public const val ARROW_UPWARD: String = "\ue5d8" // arrow_upward
    public const val PAUSE: String = "\ue034" // pause
    public const val HOURGLASS_TOP: String = "\uea5b" // hourglass_top
    public const val ERROR: String = "\uf8b6" // error
    public const val ADD: String = "\ue145" // add
    public const val PLAY_ARROW: String = "\ue037" // play_arrow
    public const val DELETE: String = "\ue92e" // delete
    public const val RESTART_ALT: String = "\uf053" // restart_alt
    public const val SEARCH: String = "\uef7a" // search
    public const val TUNE: String = "\ue429" // tune
    public const val RIGHT_PANEL_OPEN: String = "\uf704" // right_panel_open
    public const val CONTENT_COPY: String = "\ue14d" // content_copy
    public const val CAMPAIGN: String = "\uef49" // campaign
    public const val FOLDER: String = "\ue2c7" // folder
    public const val DESCRIPTION: String = "\ue873" // description
    public const val CHECK_BOX: String = "\ue9de" // check_box
    public const val CHECK_BOX_OUTLINE_BLANK: String = "\ue835" // check_box_outline_blank
    public const val ARROW_DROP_DOWN: String = "\ue5c5" // arrow_drop_down
    public const val RADIO_BUTTON_CHECKED: String = "\ue837" // radio_button_checked
    public const val RADIO_BUTTON_UNCHECKED: String = "\ue836" // radio_button_unchecked
    public const val DOWNLOAD: String = "\uf090" // download
    public const val CLOSE: String = "\ue5cd" // close
}

internal val MaterialSymbols: FontFamily =
    FontFamily(
        Font(
            resource = "fonts/MaterialSymbolsRounded.ttf",
            weight = FontWeight.Normal,
            variationSettings =
                FontVariation.Settings(
                    FontVariation.weight(400),
                    // FILL 0 and optical size 20, which is what the inventory specifies.
                    FontVariation.Setting("FILL", 0f),
                    FontVariation.Setting("opsz", 20f),
                ),
        ),
    )

/** One glyph, at the size the caller draws it. */
@Composable
public fun Glyph(
    name: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 15.sp,
    tint: Color = LocalContentColor.current,
) {
    Text(
        text = name,
        modifier = modifier,
        style = TextStyle(fontFamily = MaterialSymbols, fontSize = size),
        color = tint,
    )
}
