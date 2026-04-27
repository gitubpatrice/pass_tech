import 'package:uuid/uuid.dart';

const _uuid = Uuid();

enum EntryType { password, note, card }

EntryType entryTypeFromString(String? s) {
  switch (s) {
    case 'note': return EntryType.note;
    case 'card': return EntryType.card;
    default:     return EntryType.password;
  }
}

String entryTypeToString(EntryType t) {
  switch (t) {
    case EntryType.note: return 'note';
    case EntryType.card: return 'card';
    case EntryType.password: return 'password';
  }
}

String entryTypeLabel(EntryType t) {
  switch (t) {
    case EntryType.password: return 'Mot de passe';
    case EntryType.note:     return 'Note sécurisée';
    case EntryType.card:     return 'Carte bancaire';
  }
}

class Entry {
  final String id;
  EntryType type;
  String title;
  String category;
  // Password type
  String username;
  String password;
  String url;
  String totpSecret;
  // Common
  String notes;
  bool isFavorite;
  // Card type
  String cardholderName;
  String cardNumber;
  String cardExpiry;   // "MM/YY"
  String cardCvv;
  String cardPin;
  String cardIssuer;
  // Timestamps
  final DateTime createdAt;
  DateTime updatedAt;

  Entry({
    String? id,
    this.type = EntryType.password,
    required this.title,
    required this.category,
    this.username       = '',
    this.password       = '',
    this.url            = '',
    this.totpSecret     = '',
    this.notes          = '',
    this.isFavorite     = false,
    this.cardholderName = '',
    this.cardNumber     = '',
    this.cardExpiry     = '',
    this.cardCvv        = '',
    this.cardPin        = '',
    this.cardIssuer     = '',
    DateTime? createdAt,
    DateTime? updatedAt,
  })  : id = id ?? _uuid.v4(),
        createdAt = createdAt ?? DateTime.now(),
        updatedAt = updatedAt ?? DateTime.now();

  Entry copyWith({
    EntryType? type,
    String? title,
    String? category,
    String? username,
    String? password,
    String? url,
    String? totpSecret,
    String? notes,
    bool? isFavorite,
    String? cardholderName,
    String? cardNumber,
    String? cardExpiry,
    String? cardCvv,
    String? cardPin,
    String? cardIssuer,
  }) =>
      Entry(
        id: id,
        type:           type           ?? this.type,
        title:          title          ?? this.title,
        category:       category       ?? this.category,
        username:       username       ?? this.username,
        password:       password       ?? this.password,
        url:            url            ?? this.url,
        totpSecret:     totpSecret     ?? this.totpSecret,
        notes:          notes          ?? this.notes,
        isFavorite:     isFavorite     ?? this.isFavorite,
        cardholderName: cardholderName ?? this.cardholderName,
        cardNumber:     cardNumber     ?? this.cardNumber,
        cardExpiry:     cardExpiry     ?? this.cardExpiry,
        cardCvv:        cardCvv        ?? this.cardCvv,
        cardPin:        cardPin        ?? this.cardPin,
        cardIssuer:     cardIssuer     ?? this.cardIssuer,
        createdAt: createdAt,
        updatedAt: DateTime.now(),
      );

  factory Entry.fromJson(Map<String, dynamic> json) => Entry(
        id:             json['id']             as String,
        type:           entryTypeFromString(json['type'] as String?),
        title:          json['title']          as String,
        category:       json['category']       as String? ?? 'Autres',
        username:       json['username']       as String? ?? '',
        password:       json['password']       as String? ?? '',
        url:            json['url']            as String? ?? '',
        totpSecret:     json['totpSecret']     as String? ?? '',
        notes:          json['notes']          as String? ?? '',
        isFavorite:     json['isFavorite']     as bool?   ?? false,
        cardholderName: json['cardholderName'] as String? ?? '',
        cardNumber:     json['cardNumber']     as String? ?? '',
        cardExpiry:     json['cardExpiry']     as String? ?? '',
        cardCvv:        json['cardCvv']        as String? ?? '',
        cardPin:        json['cardPin']        as String? ?? '',
        cardIssuer:     json['cardIssuer']     as String? ?? '',
        createdAt: DateTime.parse(json['createdAt'] as String),
        updatedAt: DateTime.parse(json['updatedAt'] as String),
      );

  Map<String, dynamic> toJson() => {
        'id':             id,
        'type':           entryTypeToString(type),
        'title':          title,
        'category':       category,
        'username':       username,
        'password':       password,
        'url':            url,
        'totpSecret':     totpSecret,
        'notes':          notes,
        'isFavorite':     isFavorite,
        'cardholderName': cardholderName,
        'cardNumber':     cardNumber,
        'cardExpiry':     cardExpiry,
        'cardCvv':        cardCvv,
        'cardPin':        cardPin,
        'cardIssuer':     cardIssuer,
        'createdAt':      createdAt.toIso8601String(),
        'updatedAt':      updatedAt.toIso8601String(),
      };
}
