# S3 → OneDrive Gateway

Une passerelle HTTP qui expose une **API compatible Amazon S3** tout en utilisant **Microsoft OneDrive** (via Microsoft Graph) comme backend de stockage.

```
Client S3 (aws-cli, SDK, Terraform, rclone…)
        │  HTTP · AWS Signature V4
        ▼
┌────────────────────────────┐
│   S3 OneDrive Gateway      │
│  · Spring Boot 4 / Java 21 │
│  · Validation SigV4        │
│  · Multipart Upload        │
└────────────────────────────┘
        │  HTTPS · OAuth 2.0 Client Credentials
        ▼
   Microsoft Graph API
   (/drives  /driveItems)
```

---

## Fonctionnalités

| Opération S3             | Statut | Détail                                        |
|--------------------------|--------|-----------------------------------------------|
| `ListBuckets`            | ✅     | Dossiers sous `/{rootFolder}`                  |
| `CreateBucket`           | ✅     | Crée un dossier OneDrive                       |
| `DeleteBucket`           | ✅     | Refuse les buckets non vides (`BucketNotEmpty`)|
| `HeadBucket`             | ✅     |                                                |
| `ListObjects` (V1 & V2)  | ✅     | Récursif, préfixe + délimiteur (CommonPrefixes), pagination Graph (`nextLink`) suivie, continuation tokens, `encoding-type=url` |
| `GetObject`              | ✅     | Streaming, `Range` (206), conditionnel (`If-None-Match`/`If-Match`) |
| `PutObject`              | ✅     | ≤4 MB simple, sinon session résumable ; corps `aws-chunked` décodé à la volée |
| `CopyObject`             | ✅     | Via `x-amz-copy-source` (download → upload)    |
| `DeleteObject`           | ✅     |                                                |
| `DeleteObjects` (batch)  | ✅     | `POST /{bucket}?delete`, mode `Quiet` supporté |
| `HeadObject`             | ✅     | Taille, ETag, Last-Modified                    |
| `CreateMultipartUpload`  | ✅     |                                                |
| `UploadPart`             | ✅     | Parts spoolées sur disque temporaire           |
| `CompleteMultipartUpload`| ✅     | Assemblage en chunks de 8 MB via session résumable Graph, validation des ETags de parts |
| `AbortMultipartUpload`   | ✅     | Nettoie les fichiers temporaires               |
| SigV4 (header)           | ✅     | HMAC-SHA256 complet + payloads `STREAMING-AWS4-HMAC-SHA256-PAYLOAD` (décodage et vérification des signatures de chunks) |
| SigV4 (pre-signed)       | ✅     | Vérification HMAC complète + fenêtre d'expiration |

Gestion d'erreurs : codes d'erreur S3 (`NoSuchKey`, `NoSuchBucket`, `BucketNotEmpty`, `InvalidPart`, `InvalidRange`, `SignatureDoesNotMatch`, `RequestTimeTooSkewed`…). Les erreurs de throttling Graph (429/503) sont retentées avec backoff exponentiel.

---

## Prérequis

- **Java 21** et **Maven 3.9+** (ou Docker)
- **App Registration Azure AD** avec la permission Graph `Files.ReadWrite.All` (application, pas delegated)

---

## Configuration Azure AD

1. **Portail Azure → Entra ID → App registrations → New registration**
2. Notez le `Application (client) ID` et le `Directory (tenant) ID`
3. **Certificates & secrets → New client secret** – copiez la valeur
4. **API permissions → Add permission → Microsoft Graph → Application permissions → Files.ReadWrite.All → Grant admin consent**

> Ne commitez jamais le secret : utilisez les variables d'environnement (`.env`).
> Pour cibler un **SharePoint / drive partagé**, récupérez le `driveId` via :
> ```
> GET https://graph.microsoft.com/v1.0/sites/{site-id}/drives
> ```

---

## Démarrage rapide

### Avec Docker Compose

```bash
cp env.example .env
# Éditez .env avec vos identifiants Azure

docker compose up --build
```

### En local (Maven)

```bash
export AZURE_TENANT_ID=xxxx
export AZURE_CLIENT_ID=xxxx
export AZURE_CLIENT_SECRET=xxxx
export S3_ACCESS_KEY=minioadmin
export S3_SECRET_KEY=minioadmin

mvn spring-boot:run
```

### Vérification santé

```bash
curl http://localhost:8080/health
# {"status":"UP","service":"s3-onedrive-gateway"}

curl http://localhost:8080/actuator/health   # Spring Actuator
curl http://localhost:8080/actuator/metrics  # métriques (Micrometer)
```

---

## Utilisation avec aws-cli

```bash
# Configuration du profil
aws configure --profile onedrive
# AWS Access Key ID:     minioadmin
# AWS Secret Access Key: minioadmin
# Default region name:   us-east-1   ← doit correspondre à S3_REGION

alias s3od="aws s3 --profile onedrive --endpoint-url http://localhost:8080"

# Créer un bucket
s3od mb s3://mon-bucket

# Lister les buckets
s3od ls

# Upload d'un fichier
s3od cp rapport.pdf s3://mon-bucket/2024/rapport.pdf

# Lister les objets (récursif, pagination gérée)
s3od ls s3://mon-bucket/
s3od ls --recursive s3://mon-bucket/

# Télécharger
s3od cp s3://mon-bucket/2024/rapport.pdf ./rapport-local.pdf

# Copie côté serveur (download → upload via la passerelle)
s3od cp s3://mon-bucket/a.pdf s3://mon-bucket/copies/a.pdf

# Suppression récursive (DeleteObjects batch)
s3od rm --recursive s3://mon-bucket/vieux/

# Upload multipart (automatique pour les fichiers > 8 MB)
s3od cp gros-fichier.zip s3://mon-bucket/archives/
```

---

## Utilisation avec rclone

```ini
# ~/.config/rclone/rclone.conf
[onedrive-s3]
type = s3
provider = Other
access_key_id = minioadmin
secret_access_key = minioadmin
region = us-east-1
endpoint = http://localhost:8080
```

```bash
rclone ls onedrive-s3:mon-bucket
rclone copy ./dossier onedrive-s3:mon-bucket/dossier
```

---

## Utilisation avec le SDK Java (AWS SDK v2)

```java
S3Client s3 = S3Client.builder()
    .endpointOverride(URI.create("http://localhost:8080"))
    .region(Region.US_EAST_1)
    .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create("minioadmin", "minioadmin")))
    .forcePathStyle(true)   // ← obligatoire
    .build();

// List buckets
s3.listBuckets().buckets().forEach(b -> System.out.println(b.name()));

// Put object
s3.putObject(
    PutObjectRequest.builder().bucket("mon-bucket").key("hello.txt").build(),
    RequestBody.fromString("Hello OneDrive!"));
```

---

## Architecture interne

```
src/main/java/io/github/radixhomework/s3onedrive/
├── GatewayApplication.java
├── auth/
│   ├── AwsSigV4AuthFilter.java      ← Validation SigV4 + décodage aws-chunked
│   ├── AwsChunkedInputStream.java   ← Décodage STREAMING-AWS4-HMAC-SHA256-PAYLOAD
│   ├── SigV4.java                   ← Primitives SigV4 (canonicalisation, HMAC)
│   └── OneDriveTokenService.java    ← OAuth2 Client Credentials + cache Caffeine
├── config/
│   ├── OneDriveProperties.java
│   ├── S3Properties.java
│   ├── MultipartProperties.java
│   ├── SecurityConfig.java
│   └── WebClientConfig.java
├── controller/
│   ├── BucketController.java        ← GET / · PUT/DELETE/HEAD /{bucket} · POST ?delete
│   ├── ObjectController.java        ← Opérations objet + multipart + copy + range
│   ├── HealthController.java
│   └── GlobalExceptionHandler.java  ← Erreurs S3 en XML
├── exception/
│   └── S3Exception.java             ← (status HTTP, code S3)
├── service/
│   ├── OneDriveService.java         ← Appels Microsoft Graph (retry 429/503)
│   └── ListObjectsService.java      ← Listing récursif + continuation tokens
├── multipart/
│   └── MultipartUploadStore.java    ← État en mémoire + parts sur disque
├── model/
│   └── S3Xml.java                   ← POJOs XML S3 (Jackson XML)
└── util/
    ├── KeySanitizer.java            ← Clés S3 → noms OneDrive valides
    └── XmlUtil.java
```

---

## Mapping S3 → OneDrive

```
S3 Bucket  →  Dossier  /{rootFolder}/{bucket}/
S3 Key     →  Fichier  /{rootFolder}/{bucket}/{key}

Exemple :
  s3://mon-bucket/docs/rapport.pdf
  →  /s3/mon-bucket/docs/rapport.pdf  (dans OneDrive)
```

Les caractères interdits dans les noms OneDrive (`" * : < > ? | \`), les noms
réservés Windows (`CON`, `NUL`, `COM1`…) et les points/espaces finaux sont
encodés de façon déterministe et injective par `KeySanitizer` : la clé S3
d'origine reste identique côté client.

---

## Limites connues

| Limitation | Détail |
|---|---|
| Multipart sur disque local | Les parts sont stockées sur le disque local – pas de clustering natif ; les uploads inachevés (> 7 jours) sont purgés et les fichiers orphelins supprimés au démarrage |
| Pas de versioning S3 | OneDrive gère ses propres versions (non exposées) |
| Pas d'ACL / Policy S3 | Toute requête avec les bonnes credentials a accès complet |
| Ordre de listing | L'ordre des clés suit un DFS trié par dossier, pas strictement l'ordre lexicographique S3 sur les dossiers |
| Cohérence ETag | L'ETag retourné est celui de Graph (`eTag`), pas un MD5 S3 standard — les outils validant les checksums (ex. `rclone --checksum`) ne sont pas fiables ; les ETags de parts multipart, eux, sont de vrais MD5 |
| `CopyObject` | Implémenté comme download → upload via la passerelle (pas de copie server-side Graph asynchrone) |
| Chiffrement | Pas de SSE ; le chiffrement au repos dépend de OneDrive |

---

## Lancer les tests

```bash
mvn test
```

---

## CI

GitHub Actions (`.github/workflows/ci.yml`) : build + tests (JDK 21, Maven) puis build de l'image Docker.

---

## Variables d'environnement

| Variable | Obligatoire | Description |
|---|---|---|
| `AZURE_TENANT_ID` | ✅ | ID du tenant Azure AD |
| `AZURE_CLIENT_ID` | ✅ | ID de l'app registration |
| `AZURE_CLIENT_SECRET` | ✅ | Secret de l'app |
| `AZURE_DRIVE_ID` | ❌ | Drive SharePoint spécifique (défaut : drive perso) |
| `S3_ACCESS_KEY` | ❌ | Clé d'accès virtuelle S3 (défaut : `minioadmin`) |
| `S3_SECRET_KEY` | ❌ | Clé secrète virtuelle S3 (défaut : `minioadmin`) |
| `S3_REGION` | ❌ | Région virtuelle (défaut : `us-east-1`) — **doit correspondre** à la région configurée chez les clients, elle est vérifiée dans les signatures |
