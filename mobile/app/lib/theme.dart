import 'package:flutter/material.dart';

/// The desktop's palette, moved across rather than reinvented.
///
/// These are the values in `app/ui/app.css`, in its dark theme - the one this
/// app has no light counterpart to, because a tunnel is a night-time object
/// and the desktop's own light mode was never the one being designed for.
/// Keeping the names identical is deliberate: a colour changed there should be
/// findable here by searching for the same word.
class Tone {
  static const bg = Color(0xFF09090E);
  static const surface = Color(0xFF17171F);
  static const control = Color(0xFF20212B);
  static const controlHover = Color(0xFF292B37);
  static const controlActive = Color(0xFF313440);

  static const divider = Color(0x17FFFFFF);
  static const border = Color(0x21FFFFFF);

  static const text = Color(0xFFF5F5F8);
  static const textMuted = Color(0xFFB9BAC6);
  static const textOff = Color(0xFF777987);

  /// Three states and no more. Anything that is not working, failed, or fine
  /// is one of those three wearing a different sentence.
  static const ok = Color(0xFF61EFBF);
  static const work = Color(0xFFFFC761);
  static const fail = Color(0xFFFF8F9B);
  static const failBg = Color(0x80651D2C);

  static const accent = Color(0xFF8C82FF);
  static const blobA = Color(0xFF9C8CFF);
  static const blobB = Color(0xFF4CE2D2);
  static const blobC = Color(0xFF483AC2);
}

/// Radii and timings, also from the desktop. The easing is the one thing here
/// that is not a colour and still matters: `cubic-bezier(0, 0, 0, 1)` leaves
/// instantly and arrives slowly, which reads as the interface responding
/// rather than animating.
class Shape {
  static const card = 20.0;
  static const control = 12.0;
  static const enter = Duration(milliseconds: 167);
  static const fade = Duration(milliseconds: 83);
  static const ease = Cubic(0, 0, 0, 1);
}

ThemeData relayTheme() {
  const mono = 'monospace';

  return ThemeData(
    useMaterial3: true,
    brightness: Brightness.dark,
    scaffoldBackgroundColor: Tone.bg,
    colorScheme: const ColorScheme.dark(
      surface: Tone.surface,
      primary: Tone.accent,
      secondary: Tone.ok,
      error: Tone.fail,
      onSurface: Tone.text,
    ),
    fontFamily: 'IBMPlexSans',
    textTheme: const TextTheme(
      displaySmall: TextStyle(
          color: Tone.text, fontSize: 30, fontWeight: FontWeight.w300, height: 1.2),
      titleMedium: TextStyle(color: Tone.text, fontSize: 16),
      bodyMedium: TextStyle(color: Tone.textMuted, fontSize: 14, height: 1.5),
      bodySmall: TextStyle(color: Tone.textOff, fontSize: 12.5, height: 1.5),
      // The monospace face carries anything that is a value rather than a
      // sentence: addresses, counts, milliseconds. It is what makes a column
      // of them line up, and what stops "62.197.152.149" reading as prose.
      labelSmall: TextStyle(
          color: Tone.textOff, fontSize: 12, fontFamily: mono, letterSpacing: 0),
    ),
  );
}
