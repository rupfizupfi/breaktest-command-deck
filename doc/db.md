Based on your Docker Compose setup and the indication that you're working with a PostgreSQL custom-format dump, here are the commands for importing and exporting SQL data:

## Import SQL Commands

### For custom-format dumps (*.dump files)

```bash
# Import a custom-format dump file
docker compose -f docker/docker-compose.yaml exec -T db pg_restore -U rupfizupfi -d rupfizupfi < backup.dump

# Or with more options (clean database first, verbose output)
docker compose -f docker/docker-compose.yaml exec -T db pg_restore -U rupfizupfi -d rupfizupfi --clean --verbose < backup.dump
```

### For plain SQL files

```bash
# Import plain SQL file
docker compose -f docker/docker-compose.yaml exec -T db psql -U rupfizupfi -d rupfizupfi < script.sql
```

## Export SQL Commands

```bash
# Export as custom-format dump (compressed, supports parallel restore)
docker compose -f docker/docker-compose.yaml exec db pg_dump -U rupfizupfi -d rupfizupfi -Fc > backup.dump

# Export as plain SQL
docker compose -f docker/docker-compose.yaml exec db pg_dump -U rupfizupfi -d rupfizupfi > backup.sql

# Export specific tables only
docker compose -f docker/docker-compose.yaml exec db pg_dump -U rupfizupfi -d rupfizupfi -t table_name > table_export.sql
```

These commands leverage your Docker Compose setup and the credentials are automatically provided through the container environment.