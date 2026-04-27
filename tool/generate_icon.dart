// Generates Pass Tech launcher icon: 4 quadrants bleu/rouge + bouclier doré + cadenas blanc.
// Run: dart run tool/generate_icon.dart

// ignore_for_file: avoid_print

import 'dart:io';
import 'dart:math' as math;
import 'package:image/image.dart' as img;

const _size   = 1024;
const _half   = _size ~/ 2;
const _corner = 180;

// Quadrant colors (style PDF Tech)
final _blue  = img.ColorRgb8(21, 101, 192);
final _red   = img.ColorRgb8(198, 40, 40);

// Gold palette
final _gold     = img.ColorRgb8(249, 168, 37);
final _goldHi   = img.ColorRgb8(255, 213, 79);
final _goldDark = img.ColorRgb8(176, 109, 0);

// Lock palette (white lock with dark keyhole)
final _white     = img.ColorRgb8(255, 255, 255);
final _whiteShad = img.ColorRgb8(220, 220, 220);
final _khDark    = img.ColorRgb8(40, 30, 0);

final _outline   = img.ColorRgb8(35, 35, 35);

// Shield bounding box
const _shTop      = 180;
const _shBottom   = 900;
const _shHalfW    = 290;
const _shStraight = 540;
const _shTopRad   = 56;

// Lock geometry (à l'intérieur du bouclier)
const _lockBodyX1 = 392, _lockBodyX2 = 632;
const _lockBodyY1 = 540, _lockBodyY2 = 800;
const _lockBodyRadius = 40;
const _shackleCx = 512, _shackleCy = 480;
const _shackleOuter = 100;
const _shackleInner = 58;
const _khCx = 512, _khCy = 640;
const _khRadius = 22;
const _khStemX1 = 500, _khStemX2 = 524, _khStemY2 = 728;

img.Color _quadrantAt(int x, int y) {
  final isBlue = (x < _half) == (y < _half);
  return isBlue ? _blue : _red;
}

void _fillQuadrants(img.Image image) {
  img.fillRect(image, x1: 0,     y1: 0,     x2: _half, y2: _half, color: _blue);
  img.fillRect(image, x1: _half, y1: 0,     x2: _size, y2: _half, color: _red);
  img.fillRect(image, x1: 0,     y1: _half, x2: _half, y2: _size, color: _red);
  img.fillRect(image, x1: _half, y1: _half, x2: _size, y2: _size, color: _blue);
}

void _roundCorners(img.Image image) {
  final corners = [
    (cx: _corner,         cy: _corner,         x0: 0,             y0: 0),
    (cx: _size - _corner, cy: _corner,         x0: _size - _corner, y0: 0),
    (cx: _corner,         cy: _size - _corner, x0: 0,             y0: _size - _corner),
    (cx: _size - _corner, cy: _size - _corner, x0: _size - _corner, y0: _size - _corner),
  ];
  for (final c in corners) {
    for (int dy = 0; dy < _corner; dy++) {
      for (int dx = 0; dx < _corner; dx++) {
        final px = c.x0 + dx;
        final py = c.y0 + dy;
        final ddx = px - c.cx;
        final ddy = py - c.cy;
        if (ddx * ddx + ddy * ddy > _corner * _corner) {
          image.setPixel(px, py, _quadrantAt(px, py));
        }
      }
    }
  }
}

int _shieldHalfWidthAt(int y, [int extra = 0]) {
  if (y < _shTop || y > _shBottom) return 0;
  final hw = _shHalfW + extra;
  if (y <= _shStraight) {
    if (y < _shTop + _shTopRad) {
      final dy = (_shTop + _shTopRad) - y;
      final r2 = _shTopRad * _shTopRad - dy * dy;
      if (r2 < 0) return 0;
      return (hw - _shTopRad) + math.sqrt(r2).round();
    }
    return hw;
  }
  final t = (y - _shStraight) / (_shBottom - _shStraight);
  final factor = 1.0 - math.pow(t, 1.6).toDouble();
  final w = (hw * factor).round();
  return w < 0 ? 0 : w;
}

void _fillShield(img.Image image, img.Color color, {int extra = 0, int yStart = 0, int yEnd = _size}) {
  for (int y = math.max(yStart, _shTop - extra); y < math.min(yEnd, _shBottom + extra + 1); y++) {
    final hw = _shieldHalfWidthAt(y, extra);
    if (hw <= 0) continue;
    img.fillRect(image,
        x1: _half - hw, y1: y, x2: _half + hw, y2: y + 1, color: color);
  }
}

void _drawShield(img.Image image) {
  // Contour
  _fillShield(image, _outline, extra: 8);
  // Bouclier blanc
  _fillShield(image, _white);

  // Bande lumineuse à gauche (très subtile)
  for (int y = _shTop + 24; y < _shBottom - 24; y++) {
    final hw = _shieldHalfWidthAt(y);
    if (hw <= 0) continue;
    final hiW = (hw * 0.10).round();
    img.fillRect(image,
        x1: _half - hw + 12, y1: y,
        x2: _half - hw + 12 + hiW, y2: y + 1,
        color: img.ColorRgb8(245, 245, 250));
  }

  // Bande d'ombre à droite (gris léger pour donner du volume)
  for (int y = _shTop + 30; y < _shBottom - 30; y++) {
    final hw = _shieldHalfWidthAt(y);
    if (hw <= 0) continue;
    final shW = (hw * 0.16).round();
    img.fillRect(image,
        x1: _half + hw - shW - 14, y1: y,
        x2: _half + hw - 14, y2: y + 1,
        color: _whiteShad);
  }
}

void _roundRect(img.Image image, int x1, int y1, int x2, int y2, int r, img.Color c) {
  img.fillRect(image, x1: x1 + r, y1: y1, x2: x2 - r, y2: y2, color: c);
  img.fillRect(image, x1: x1, y1: y1 + r, x2: x2, y2: y2 - r, color: c);
  img.fillCircle(image, x: x1 + r, y: y1 + r, radius: r, color: c);
  img.fillCircle(image, x: x2 - r, y: y1 + r, radius: r, color: c);
  img.fillCircle(image, x: x1 + r, y: y2 - r, radius: r, color: c);
  img.fillCircle(image, x: x2 - r, y: y2 - r, radius: r, color: c);
}

// Restaure la couleur du bouclier (blanc) pour creuser dans le cadenas
void _restoreShieldCircle(img.Image image, int cx, int cy, int radius) {
  final r2 = radius * radius;
  for (int y = cy - radius; y <= cy + radius; y++) {
    for (int x = cx - radius; x <= cx + radius; x++) {
      final dx = x - cx;
      final dy = y - cy;
      if (dx * dx + dy * dy > r2) continue;
      image.setPixel(x, y, _white);
    }
  }
}

void _restoreShieldRect(img.Image image, int x1, int y1, int x2, int y2) {
  for (int y = y1; y < y2; y++) {
    for (int x = x1; x < x2; x++) {
      image.setPixel(x, y, _white);
    }
  }
}

void _drawLock(img.Image image) {
  // Contour cadenas
  img.fillCircle(image, x: _shackleCx, y: _shackleCy,
      radius: _shackleOuter + 6, color: _outline);
  _roundRect(image,
      _lockBodyX1 - 6, _lockBodyY1 - 6,
      _lockBodyX2 + 6, _lockBodyY2 + 6,
      _lockBodyRadius + 4, _outline);

  // Shackle doré (anneau)
  img.fillCircle(image, x: _shackleCx, y: _shackleCy,
      radius: _shackleOuter, color: _gold);
  _restoreShieldCircle(image, _shackleCx, _shackleCy, _shackleInner);

  // Cover bottom of shackle → U-shape
  _restoreShieldRect(image,
      _shackleCx - _shackleOuter - 4, _shackleCy + 28,
      _shackleCx + _shackleOuter + 4, _lockBodyY1 - 6);

  // Highlight clair sur le shackle (côté gauche)
  for (int y = _shackleCy - _shackleOuter + 10; y < _shackleCy; y++) {
    for (int x = _shackleCx - _shackleOuter + 6; x < _shackleCx - _shackleInner - 8; x++) {
      final dx = x - _shackleCx;
      final dy = y - _shackleCy;
      final d2 = dx * dx + dy * dy;
      if (d2 <= (_shackleOuter - 8) * (_shackleOuter - 8) &&
          d2 >= (_shackleInner + 6) * (_shackleInner + 6)) {
        image.setPixel(x, y, _goldHi);
      }
    }
  }

  // Body cadenas doré
  _roundRect(image, _lockBodyX1, _lockBodyY1, _lockBodyX2, _lockBodyY2,
      _lockBodyRadius, _gold);

  // Highlight gauche du body (bande verticale claire)
  img.fillRect(image,
      x1: _lockBodyX1 + 14, y1: _lockBodyY1 + 18,
      x2: _lockBodyX1 + 38, y2: _lockBodyY2 - 18,
      color: _goldHi);

  // Ombre droite du body (bronze)
  img.fillRect(image,
      x1: _lockBodyX2 - 32, y1: _lockBodyY1 + 18,
      x2: _lockBodyX2 - 12, y2: _lockBodyY2 - 18,
      color: _goldDark);

  // Keyhole (cercle + tige) en sombre — contraste sur l'or
  img.fillCircle(image, x: _khCx, y: _khCy, radius: _khRadius + 3, color: _outline);
  img.fillRect(image,
      x1: _khStemX1 - 3, y1: _khCy,
      x2: _khStemX2 + 3, y2: _khStemY2 + 3,
      color: _outline);

  img.fillCircle(image, x: _khCx, y: _khCy, radius: _khRadius, color: _khDark);
  img.fillRect(image,
      x1: _khStemX1, y1: _khCy,
      x2: _khStemX2, y2: _khStemY2,
      color: _khDark);
}

void main() {
  final image = img.Image(width: _size, height: _size);

  _fillQuadrants(image);
  _roundCorners(image);
  _drawShield(image);
  _drawLock(image);

  Directory('assets').createSync(recursive: true);
  File('assets/icon.png').writeAsBytesSync(img.encodePng(image));
  print('Wrote assets/icon.png ($_size x $_size)');

  const sizes = {
    'mdpi':    48,
    'hdpi':    72,
    'xhdpi':   96,
    'xxhdpi':  144,
    'xxxhdpi': 192,
  };
  for (final entry in sizes.entries) {
    final resized = img.copyResize(image,
        width: entry.value,
        height: entry.value,
        interpolation: img.Interpolation.cubic);
    final path = 'android/app/src/main/res/mipmap-${entry.key}/ic_launcher.png';
    File(path).writeAsBytesSync(img.encodePng(resized));
    print('Wrote $path (${entry.value}x${entry.value})');
  }

  print('\nLauncher icon generated successfully.');
}
