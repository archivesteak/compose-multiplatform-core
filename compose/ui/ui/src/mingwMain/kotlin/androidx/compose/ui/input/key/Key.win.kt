/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.input.key

import androidx.compose.ui.input.key.Key.Companion.Number

/**
 * Actual implementation of [Key] for JS and Native.
 *
 * @param keyCode an integer code representing the key pressed. Note: This keycode can be used to
 * uniquely identify a hardware key.
 */
actual value class Key(val keyCode: Long) {
    actual companion object {
        /** Unknown key. */
        actual val Unknown = Key(-1)

        /**
         * Home key.
         *
         * This key is handled by the framework and is never delivered to applications.
         */
        actual val Home = Key(36)

        /**
         * Up Arrow Key / Directional Pad Up key.
         *
         * May also be synthesized from trackball motions.
         */
        actual val DirectionUp = Key(38)

        /**
         * Down Arrow Key / Directional Pad Down key.
         *
         * May also be synthesized from trackball motions.
         */
        actual val DirectionDown = Key(40)

        /**
         * Left Arrow Key / Directional Pad Left key.
         *
         * May also be synthesized from trackball motions.
         */
        actual val DirectionLeft = Key(37)

        /**
         * Right Arrow Key / Directional Pad Right key.
         *
         * May also be synthesized from trackball motions.
         */
        actual val DirectionRight = Key(39)

        /** '0' key. */
        actual val Zero = Key(48)

        /** '1' key. */
        actual val One = Key(49)

        /** '2' key. */
        actual val Two = Key(50)

        /** '3' key. */
        actual val Three = Key(51)

        /** '4' key. */
        actual val Four = Key(52)

        /** '5' key. */
        actual val Five = Key(53)

        /** '6' key. */
        actual val Six = Key(54)

        /** '7' key. */
        actual val Seven = Key(55)

        /** '8' key. */
        actual val Eight = Key(56)

        /** '9' key. */
        actual val Nine = Key(57)

        /** '-' key. */
        actual val Minus = Key(189)

        /** '=' key. */
        actual val Equals = Key(187)

        /** 'A' key. */
        actual val A = Key(65)

        /** 'B' key. */
        actual val B = Key(66)

        /** 'C' key. */
        actual val C = Key(67)

        /** 'D' key. */
        actual val D = Key(68)

        /** 'E' key. */
        actual val E = Key(69)

        /** 'F' key. */
        actual val F = Key(70)

        /** 'G' key. */
        actual val G = Key(71)

        /** 'H' key. */
        actual val H = Key(72)

        /** 'I' key. */
        actual val I = Key(73)

        /** 'J' key. */
        actual val J = Key(74)

        /** 'K' key. */
        actual val K = Key(75)

        /** 'L' key. */
        actual val L = Key(76)

        /** 'M' key. */
        actual val M = Key(77)

        /** 'N' key. */
        actual val N = Key(78)

        /** 'O' key. */
        actual val O = Key(79)

        /** 'P' key. */
        actual val P = Key(80)

        /** 'Q' key. */
        actual val Q = Key(81)

        /** 'R' key. */
        actual val R = Key(82)

        /** 'S' key. */
        actual val S = Key(83)

        /** 'T' key. */
        actual val T = Key(84)

        /** 'U' key. */
        actual val U = Key(85)

        /** 'V' key. */
        actual val V = Key(86)

        /** 'W' key. */
        actual val W = Key(87)

        /** 'X' key. */
        actual val X = Key(88)

        /** 'Y' key. */
        actual val Y = Key(89)

        /** 'Z' key. */
        actual val Z = Key(90)

        /** ',' key. */
        actual val Comma = Key(188)

        /** '.' key. */
        actual val Period = Key(190)

        /** Left Alt modifier key. */
        actual val AltLeft = Key(164)

        /** Right Alt modifier key. */
        actual val AltRight = Key(165)

        /** Left Shift modifier key. */
        actual val ShiftLeft = Key(160)

        /** Right Shift modifier key. */
        actual val ShiftRight = Key(161)

        /** Tab key. */
        actual val Tab = Key(9)

        /** Space key. */
        actual val Spacebar = Key(32)

        /** Enter key. */
        actual val Enter = Key(13)

        /**
         * Backspace key.
         *
         * Deletes characters before the insertion point, unlike [Delete].
         */
        actual val Backspace = Key(8)

        /**
         * Delete key.
         *
         * Deletes characters ahead of the insertion point, unlike [Backspace].
         */
        actual val Delete = Key(46)

        /** Escape key. */
        actual val Escape = Key(27)

        /** Left Control modifier key. */
        actual val CtrlLeft = Key(162)

        /** Right Control modifier key. */
        actual val CtrlRight = Key(163)

        /** Caps Lock key. */
        actual val CapsLock = Key(20)

        /** Scroll Lock key. */
        actual val ScrollLock = Key(145)

        /** Left Meta modifier key. */
        actual val MetaLeft = Key(91)

        /** Right Meta modifier key. */
        actual val MetaRight = Key(92)

        /** System Request / Print Screen key. */
        actual val PrintScreen = Key(44)

        /**
         * Insert key.
         *
         * Toggles insert / overwrite edit mode.
         */
        actual val Insert = Key(45)

        /** '`' (backtick) key. */
        actual val Grave = Key(192)

        /** '[' key. */
        actual val LeftBracket = Key(219)

        /** ']' key. */
        actual val RightBracket = Key(221)

        /** '/' key. */
        actual val Slash = Key(191)

        /** '\' key. */
        actual val Backslash = Key(220)

        /** ';' key. */
        actual val Semicolon = Key(186)

        /** Page Up key. */
        actual val PageUp = Key(33)

        /** Page Down key. */
        actual val PageDown = Key(34)

        /** F1 key. */
        actual val F1 = Key(112)

        /** F2 key. */
        actual val F2 = Key(113)

        /** F3 key. */
        actual val F3 = Key(114)

        /** F4 key. */
        actual val F4 = Key(115)

        /** F5 key. */
        actual val F5 = Key(116)

        /** F6 key. */
        actual val F6 = Key(117)

        /** F7 key. */
        actual val F7 = Key(118)

        /** F8 key. */
        actual val F8 = Key(119)

        /** F9 key. */
        actual val F9 = Key(120)

        /** F10 key. */
        actual val F10 = Key(121)

        /** F11 key. */
        actual val F11 = Key(122)

        /** F12 key. */
        actual val F12 = Key(123)

        /**
         * Num Lock key.
         *
         * This is the Num Lock key; it is different from [Number].
         * This key alters the behavior of other keys on the numeric keypad.
         */
        actual val NumLock = Key(144)

        /** Numeric keypad '0' key. */
        actual val NumPad0 = Key(96)

        /** Numeric keypad '1' key. */
        actual val NumPad1 = Key(97)

        /** Numeric keypad '2' key. */
        actual val NumPad2 = Key(98)

        /** Numeric keypad '3' key. */
        actual val NumPad3 = Key(99)

        /** Numeric keypad '4' key. */
        actual val NumPad4 = Key(100)

        /** Numeric keypad '5' key. */
        actual val NumPad5 = Key(101)

        /** Numeric keypad '6' key. */
        actual val NumPad6 = Key(102)

        /** Numeric keypad '7' key. */
        actual val NumPad7 = Key(103)

        /** Numeric keypad '8' key. */
        actual val NumPad8 = Key(104)

        /** Numeric keypad '9' key. */
        actual val NumPad9 = Key(105)

        /** Numeric keypad '/' key (for division). */
        actual val NumPadDivide = Key(111)

        /** Numeric keypad '*' key (for multiplication). */
        actual val NumPadMultiply = Key(106)

        /** Numeric keypad '-' key (for subtraction). */
        actual val NumPadSubtract = Key(109)

        /** Numeric keypad '+' key (for addition). */
        actual val NumPadAdd = Key(107)

        /** Numeric keypad Enter key. */
        actual val NumPadEnter = Key(13)

        actual val MoveHome = Key(36)

        actual val MoveEnd = Key(35)

        // Unsupported Keys
        actual val SoftLeft = Key(0)
        actual val SoftRight = Key(0)
        actual val Back = Key(0)
        actual val NavigatePrevious = Key(0)
        actual val NavigateNext = Key(0)
        actual val NavigateIn = Key(0)
        actual val NavigateOut = Key(0)
        actual val SystemNavigationUp = Key(0)
        actual val SystemNavigationDown = Key(0)
        actual val SystemNavigationLeft = Key(0)
        actual val SystemNavigationRight = Key(0)
        actual val Call = Key(0)
        actual val EndCall = Key(0)
        actual val DirectionCenter = Key(0)
        actual val DirectionUpLeft = Key(0)
        actual val DirectionDownLeft = Key(0)
        actual val DirectionUpRight = Key(0)
        actual val DirectionDownRight = Key(0)
        actual val VolumeUp = Key(175)
        actual val VolumeDown = Key(174)
        actual val Power = Key(0)
        actual val Camera = Key(0)
        actual val Clear = Key(12)
        actual val Symbol = Key(0)
        actual val Browser = Key(0)
        actual val Envelope = Key(180)
        actual val Function = Key(0)
        actual val Break = Key(19)
        actual val Number = Key(0)
        actual val HeadsetHook = Key(0)
        actual val Focus = Key(0)
        actual val Menu = Key(93)
        actual val Notification = Key(0)
        actual val Search = Key(170)
        actual val PictureSymbols = Key(0)
        actual val SwitchCharset = Key(0)
        actual val ButtonA = Key(0)
        actual val ButtonB = Key(0)
        actual val ButtonC = Key(0)
        actual val ButtonX = Key(0)
        actual val ButtonY = Key(0)
        actual val ButtonZ = Key(0)
        actual val ButtonL1 = Key(0)
        actual val ButtonR1 = Key(0)
        actual val ButtonL2 = Key(0)
        actual val ButtonR2 = Key(0)
        actual val ButtonThumbLeft = Key(0)
        actual val ButtonThumbRight = Key(0)
        actual val ButtonStart = Key(0)
        actual val ButtonSelect = Key(0)
        actual val ButtonMode = Key(0)
        actual val Button1 = Key(0)
        actual val Button2 = Key(0)
        actual val Button3 = Key(0)
        actual val Button4 = Key(0)
        actual val Button5 = Key(0)
        actual val Button6 = Key(0)
        actual val Button7 = Key(0)
        actual val Button8 = Key(0)
        actual val Button9 = Key(0)
        actual val Button10 = Key(0)
        actual val Button11 = Key(0)
        actual val Button12 = Key(0)
        actual val Button13 = Key(0)
        actual val Button14 = Key(0)
        actual val Button15 = Key(0)
        actual val Button16 = Key(0)
        actual val Forward = Key(167)
        actual val MediaPlay = Key(250)
        actual val MediaPause = Key(0)
        actual val MediaPlayPause = Key(179)
        actual val MediaStop = Key(178)
        actual val MediaRecord = Key(0)
        actual val MediaNext = Key(176)
        actual val MediaPrevious = Key(177)
        actual val MediaRewind = Key(0)
        actual val MediaFastForward = Key(0)
        actual val MediaClose = Key(0)
        actual val MediaAudioTrack = Key(0)
        actual val MediaEject = Key(0)
        actual val MediaTopMenu = Key(0)
        actual val MediaSkipForward = Key(0)
        actual val MediaSkipBackward = Key(0)
        actual val MediaStepForward = Key(0)
        actual val MediaStepBackward = Key(0)
        actual val MicrophoneMute = Key(0)
        actual val VolumeMute = Key(173)
        actual val Info = Key(0)
        actual val ChannelUp = Key(0)
        actual val ChannelDown = Key(0)
        actual val ZoomIn = Key(251)
        actual val ZoomOut = Key(0)
        actual val Tv = Key(0)
        actual val Window = Key(0)
        actual val Guide = Key(0)
        actual val Dvr = Key(0)
        actual val Bookmark = Key(171)
        actual val Captions = Key(0)
        actual val Settings = Key(0)
        actual val TvPower = Key(0)
        actual val TvInput = Key(0)
        actual val SetTopBoxPower = Key(0)
        actual val SetTopBoxInput = Key(0)
        actual val AvReceiverPower = Key(0)
        actual val AvReceiverInput = Key(0)
        actual val ProgramRed = Key(0)
        actual val ProgramGreen = Key(0)
        actual val ProgramYellow = Key(0)
        actual val ProgramBlue = Key(0)
        actual val AppSwitch = Key(0)
        actual val LanguageSwitch = Key(0)
        actual val MannerMode = Key(0)
        actual val Toggle2D3D = Key(0)
        actual val Contacts = Key(0)
        actual val Calendar = Key(0)
        actual val Music = Key(0)
        actual val Calculator = Key(0)
        actual val ZenkakuHankaru = Key(25)
        actual val Eisu = Key(0)
        actual val Muhenkan = Key(29)
        actual val Henkan = Key(28)
        actual val KatakanaHiragana = Key(0)
        actual val Yen = Key(0)
        actual val Ro = Key(0)
        actual val Kana = Key(21)
        actual val Assist = Key(0)
        actual val BrightnessDown = Key(0)
        actual val BrightnessUp = Key(0)
        actual val Sleep = Key(95)
        actual val WakeUp = Key(0)
        actual val SoftSleep = Key(0)
        actual val Pairing = Key(0)
        actual val LastChannel = Key(0)
        actual val TvDataService = Key(0)
        actual val VoiceAssist = Key(0)
        actual val TvRadioService = Key(0)
        actual val TvTeletext = Key(0)
        actual val TvNumberEntry = Key(0)
        actual val TvTerrestrialAnalog = Key(0)
        actual val TvTerrestrialDigital = Key(0)
        actual val TvSatellite = Key(0)
        actual val TvSatelliteBs = Key(0)
        actual val TvSatelliteCs = Key(0)
        actual val TvSatelliteService = Key(0)
        actual val TvNetwork = Key(0)
        actual val TvAntennaCable = Key(0)
        actual val TvInputHdmi1 = Key(0)
        actual val TvInputHdmi2 = Key(0)
        actual val TvInputHdmi3 = Key(0)
        actual val TvInputHdmi4 = Key(0)
        actual val TvInputComposite1 = Key(0)
        actual val TvInputComposite2 = Key(0)
        actual val TvInputComponent1 = Key(0)
        actual val TvInputComponent2 = Key(0)
        actual val TvInputVga1 = Key(0)
        actual val TvAudioDescription = Key(0)
        actual val TvAudioDescriptionMixingVolumeUp = Key(0)
        actual val TvAudioDescriptionMixingVolumeDown = Key(0)
        actual val TvZoomMode = Key(0)
        actual val TvContentsMenu = Key(0)
        actual val TvMediaContextMenu = Key(0)
        actual val TvTimerProgramming = Key(0)
        actual val StemPrimary = Key(0)
        actual val Stem1 = Key(0)
        actual val Stem2 = Key(0)
        actual val Stem3 = Key(0)
        actual val AllApps = Key(0)
        actual val Refresh = Key(168)
        actual val ThumbsUp = Key(0)
        actual val ThumbsDown = Key(0)
        actual val ProfileSwitch = Key(0)
        actual val Help = Key(47)
        actual val Plus = Key(187)
        actual val Multiply = Key(106)
        actual val Pound = Key(0)
        actual val Cut = Key(0)
        actual val Copy = Key(0)
        actual val Paste = Key(0)
        actual val Apostrophe = Key(222)
        actual val At = Key(0)
        actual val NumPadDot = Key(110)
        actual val NumPadComma = Key(108)
        actual val NumPadEquals = Key(146)
        actual val NumPadLeftParenthesis = Key(0)
        actual val NumPadRightParenthesis = Key(0)

        // Keys with no Win32 virtual-key code of their own. The numeric keypad's navigation
        // function (NumLock off) reuses the virtual keys of the dedicated navigation block and is
        // told apart only by the extended-key flag, which the key code cannot carry. These use the
        // same synthetic codes as the other Kotlin/Native targets, so `Key` identity matches.
        actual val NumPadDirectionUp = Key(-1000000198)
        actual val NumPadDirectionDown = Key(-1000000199)
        actual val NumPadDirectionLeft = Key(-1000000200)
        actual val NumPadDirectionRight = Key(-1000000201)
        actual val NumPadMoveHome = Key(-1000000202)
        actual val NumPadMoveEnd = Key(-1000000203)
        actual val NumPadPageUp = Key(-1000000204)
        actual val NumPadPageDown = Key(-1000000205)
        actual val NumPadInsert = Key(-1000000206)
        actual val NumPadDelete = Key(-1000000208)

        /** Android's system home button; Windows has no counterpart. */
        actual val SystemHome = Key(-1000000207)
    }

    actual override fun toString() = "Key keyCode: $keyCode"
}
