import 'dart:async';
import 'package:flutter/services.dart';

class ClipboardService {
  static Timer? _timer;
  static int clearAfterSeconds = 30;

  static Future<void> copyWithAutoClear(String text, {VoidCallback? onCleared}) async {
    HapticFeedback.lightImpact();
    await Clipboard.setData(ClipboardData(text: text));
    _timer?.cancel();
    if (clearAfterSeconds > 0) {
      _timer = Timer(Duration(seconds: clearAfterSeconds), () async {
        await Clipboard.setData(const ClipboardData(text: ''));
        onCleared?.call();
      });
    }
  }

  static void cancel() {
    _timer?.cancel();
    _timer = null;
  }
}
