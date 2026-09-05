package ru.workinprogress.kachok.ui

/**
 * The module exists before the screens do.
 *
 * Phase 2's UI is being built from a design; this file is here so that the build wiring — Compose,
 * KSP, viddik's processor, the engine dependency — can be proved to work before a single screen is
 * written against a design that may say something different from what anyone guessed.
 */
internal const val MODULE_MARKER: String = "kachok-ui"
