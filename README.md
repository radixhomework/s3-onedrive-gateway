# S3 → OneDrive Gateway

Une passerelle HTTP qui expose une **API compatible Amazon S3** tout en utilisant **Microsoft OneDrive** (via Microsoft Graph) comme backend de stockage.

```
Client S3 (aws-cli, SDK, Terraform, rclone…)
        │  HTTP · AWS Signature V4
        ▼
┌────────────────────────────┐
│   S3 OneDrive Gateway      │
│  · Spring Boot 3 / Java 21 │
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

| Opération S3             | Statut | Détail                                      |
|--------------------------|--------|---------------------------------------------|
| `ListBuckets`            | ✅     | Dossiers sous `/{rootFolder}`               |
| `CreateBucket`           | ✅     | Crée un dossier OneDrive                    |
| `DeleteBucket`           | ✅     | Supprime le dossier                         |
| `HeadBucket`             | ✅     |                                             |
| `ListObjects` (V1 & V2)  | ✅     | Préfixe + délimiteur (dossiers virtuels)    |
| `GetObject`              | ✅     | Streaming                                   |
| `PutObject`              | ✅     | Simple (≤4 MB) ou session résumable (>4 MB) |
| `DeleteObject`           | ✅     |                                             |
| `HeadObject`             | ✅     | Taille, ETag, Last-Modified                 |
| `CreateMultipartUpload`  | ✅     |                                             |
| `UploadPart`             | ✅     | Parts stockées en temp-dir                  |
| `CompleteMultipartUpload`| ✅     | Assemblage via session résumable Graph      |
| `AbortMultipartUpload`   | ✅     | Nettoie les fichiers temporaires            |
| AWS SigV4 (header)       | ✅     | HMAC-SHA256 complet                         |
| AWS SigV4 (pre-signed)   | ✅     | Vérification access key                     |

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
```

---

## Utilisation avec aws-cli

```bash
# Configuration du profil
aws configure --profile onedrive
# AWS Access Key ID:     minioadmin
# AWS Secret Access Key: minioadmin
# Default region name:   us-east-1

alias s3od="aws s3 --profile onedrive --endpoint-url http://localhost:8080"

# Créer un bucket
s3od mb s3://mon-bucket

# Lister les buckets
s3od ls

# Upload d'un fichier
s3od cp rapport.pdf s3://mon-bucket/2024/rapport.pdf

# Lister les objets
s3od ls s3://mon-bucket/

# Télécharger
s3od cp s3://mon-bucket/2024/rapport.pdf ./rapport-local.pdf

# Supprimer
s3od rm s3://mon-bucket/2024/rapport.pdf

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
src/main/java/com/gateway/s3onedrive/
├── S3OneDriveGatewayApplication.java
├── auth/
│   ├── AwsSigV4AuthFilter.java      ← Validation HMAC-SHA256 de chaque requête
│   └── OneDriveTokenService.java   ← OAuth2 Client Credentials + cache Caffeine
├── config/
│   ├── OneDriveProperties.java
│   ├── S3Properties.java
│   ├── MultipartProperties.java
│   ├── SecurityConfig.java
│   └── WebClientConfig.java
├── controller/
│   ├── BucketController.java        ← GET / · PUT/DELETE/HEAD /{bucket}
│   ├── ObjectController.java        ← Toutes les opérations objet + multipart
│   ├── HealthController.java
│   └── GlobalExceptionHandler.java
├── service/
│   └── OneDriveService.java         ← Appels Microsoft Graph (WebClient réactif)
├── multipart/
│   └── MultipartUploadStore.java    ← État en mémoire + parties sur disque
├── model/
│   └── S3Xml.java                   ← POJOs XML S3 (Jackson XML)
└── util/
    └── XmlUtil.java
```

---

## Mapping S3 → OneDrive

```
S3 Bucket  →  Dossier  /{rootFolder}/{bucket}/
S3 Key     →  Fichier  /{rootFolder}/{bucket}/{key}

Exemple :
  s3://mon-bucket/docs/rapport.pdf
  →  /s3-gateway/mon-bucket/docs/rapport.pdf  (dans OneDrive)
```

---

## Limites connues

| Limitation | Détail |
|---|---|
| Multipart en mémoire | Les parties sont stockées sur le disque local – pas de clustering natif |
| Pas de versioning S3 | OneDrive gère ses propres versions (non exposées) |
| Pas d'ACL / Policy S3 | Toute requête avec les bonnes credentials a accès complet |
| Pagination Graph | ListObjects retourne max 1000 items (nextLink non suivi) |
| Cohérence ETag | L'ETag retourné est celui de Graph (`cTag`), pas un MD5 S3 standard |

---

## Lancer les tests

```bash
mvn test
```

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
| `S3_REGION` | ❌ | Région virtuelle (défaut : `us-east-1`) |
