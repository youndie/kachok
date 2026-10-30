package io.github.youndie.kachok.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.youndie.kachok.ui.session.Preferences
import io.github.youndie.kachok.ui.session.settingsOf
import io.github.youndie.kachok.ui.settings.SettingChange
import io.github.youndie.kachok.ui.settings.SettingsScreen
import io.github.youndie.kachok.ui.theme.material.MaterialColours
import io.github.youndie.kachok.ui.theme.material.MaterialKit
import io.github.youndie.kachok.ui.theme.material.SchemeStyle
import io.github.youndie.kachok.ui.theme.oldge.OldgeKit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

/**
 * The *Interface* setting redraws the window in the other kit, and back, without a restart (B-160).
 *
 * Wired the way `run` in `App.kt` wires it: the theme reads the kit off the preferences above the
 * screen, and a `Chose` from the screen writes the preferences. What is asserted is the kit the
 * *screen itself* is composed under — read from `LocalKit` inside the theme — so a switch that
 * stored the choice and left the composition on the old kit fails here.
 */
@OptIn(ExperimentalTestApi::class)
class KitSwitchTest {
    @Test
    fun pickingAKitRedrawsTheWindowInItAndBack(): Unit =
        runComposeUiTest {
            val seen = mutableListOf<KachokKit>()
            setContent {
                var preferences by remember { mutableStateOf(Preferences(directory = "/tmp/x")) }
                KachokTheme(kit = preferences.kit) {
                    val kit = LocalKit.current
                    if (seen.lastOrNull() !== kit) seen += kit
                    SettingsScreen(settingsOf(preferences)) { change ->
                        if (change is SettingChange.Chose) preferences = preferences.chose(change.key, change.value)
                    }
                }
            }
            assertSame(MaterialKit.Default, seen.last(), "the default is the Material kit")

            onNodeWithText(InterfaceKit.Oldge.label).performScrollTo().performClick()
            waitForIdle()
            assertSame(OldgeKit, seen.last(), "picking oldge-ui did not reach the composition")
            onNodeWithText(InterfaceKit.Oldge.label).assertIsSelected()
            // The screen is still there and still answers, drawn by the other kit's components.
            onNodeWithContentDescription("Join the DHT (BEP 5)").performScrollTo().performClick()

            onNodeWithText(InterfaceKit.Material.label).performScrollTo().performClick()
            waitForIdle()
            assertSame(MaterialKit.Default, seen.last(), "and back")
            assertEquals(listOf<KachokKit>(MaterialKit.Default, OldgeKit, MaterialKit.Default), seen)
        }

    /**
     * A swatch and a style redraw the window in a generated scheme and back to the design (B-161),
     * wired the same way: what is asserted is the kit the screen is composed under.
     */
    @Test
    fun pickingAColourRedrawsTheWindowInItAndBack(): Unit =
        runComposeUiTest {
            val seen = mutableListOf<KachokKit>()
            setContent {
                var preferences by remember { mutableStateOf(Preferences(directory = "/tmp/x")) }
                KachokTheme(kit = preferences.kit) {
                    val kit = LocalKit.current
                    if (seen.lastOrNull() != kit) seen += kit
                    SettingsScreen(settingsOf(preferences)) { change ->
                        when (change) {
                            is SettingChange.Chose -> preferences = preferences.chose(change.key, change.value)
                            is SettingChange.Swatched -> preferences = preferences.swatched(change.key, change.value)
                            else -> Unit
                        }
                    }
                }
            }
            assertSame(MaterialKit.Default, seen.last())

            onNodeWithContentDescription("Violet").performScrollTo().performClick()
            waitForIdle()
            val violet = seen.last() as MaterialKit
            assertEquals(MaterialColours(Color(0xFFA78BFA), SchemeStyle.TonalSpot), violet.colours)
            assertNotEquals(MaterialKit.Default.colors.accent, violet.colors.accent, "the accent did not move")
            onNodeWithContentDescription("Violet").assertIsSelected()

            onNodeWithText(SchemeStyle.Vibrant.label).performScrollTo().performClick()
            waitForIdle()
            assertEquals(SchemeStyle.Vibrant, (seen.last() as MaterialKit).colours.style)

            onNodeWithContentDescription("Teal").performScrollTo().performClick()
            onNodeWithText(SchemeStyle.TonalSpot.label).performScrollTo().performClick()
            waitForIdle()
            assertSame(MaterialKit.Default, seen.last(), "the design's teal and style are the hand-written kit")
        }

    /** Under oldge-ui the row is absent: its colours are its skin, which B-162 makes a choice. */
    @Test
    fun theColoursRowIsTheMaterialKits(): Unit =
        runComposeUiTest {
            setContent {
                KachokTheme(kit = OldgeKit) {
                    SettingsScreen(settingsOf(Preferences(directory = "/tmp/x", interfaceKit = InterfaceKit.Oldge)))
                }
            }
            onAllNodesWithContentDescription("Colours").assertCountEquals(0)
            onAllNodesWithContentDescription("Violet").assertCountEquals(0)
        }
}
