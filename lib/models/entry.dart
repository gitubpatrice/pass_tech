import 'package:uuid/uuid.dart';

const _uuid = Uuid();

class Entry {
  final String id;
  String title;
  String category;
  String username;
  String password;
  String url;
  String notes;
  bool isFavorite;
  final DateTime createdAt;
  DateTime updatedAt;

  Entry({
    String? id,
    required this.title,
    required this.category,
    required this.username,
    required this.password,
    this.url = '',
    this.notes = '',
    this.isFavorite = false,
    DateTime? createdAt,
    DateTime? updatedAt,
  })  : id = id ?? _uuid.v4(),
        createdAt = createdAt ?? DateTime.now(),
        updatedAt = updatedAt ?? DateTime.now();

  Entry copyWith({
    String? title,
    String? category,
    String? username,
    String? password,
    String? url,
    String? notes,
    bool? isFavorite,
  }) =>
      Entry(
        id: id,
        title: title ?? this.title,
        category: category ?? this.category,
        username: username ?? this.username,
        password: password ?? this.password,
        url: url ?? this.url,
        notes: notes ?? this.notes,
        isFavorite: isFavorite ?? this.isFavorite,
        createdAt: createdAt,
        updatedAt: DateTime.now(),
      );

  factory Entry.fromJson(Map<String, dynamic> json) => Entry(
        id: json['id'] as String,
        title: json['title'] as String,
        category: json['category'] as String? ?? 'Autres',
        username: json['username'] as String? ?? '',
        password: json['password'] as String? ?? '',
        url: json['url'] as String? ?? '',
        notes: json['notes'] as String? ?? '',
        isFavorite: json['isFavorite'] as bool? ?? false,
        createdAt: DateTime.parse(json['createdAt'] as String),
        updatedAt: DateTime.parse(json['updatedAt'] as String),
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'title': title,
        'category': category,
        'username': username,
        'password': password,
        'url': url,
        'notes': notes,
        'isFavorite': isFavorite,
        'createdAt': createdAt.toIso8601String(),
        'updatedAt': updatedAt.toIso8601String(),
      };
}
