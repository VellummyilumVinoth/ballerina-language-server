#!/bin/bash

# Database Migration Script for central-index.sqlite
# Version: 1.0
# Usage: ./migrate.sh [apply|rollback|status|backup]

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Paths
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB_PATH="${SCRIPT_DIR}/../flow-model-generator-ls-extension/src/main/resources/central-index.sqlite"
MIGRATION_DIR="${SCRIPT_DIR}/src/main/resources/migrations"
BACKUP_DIR="${SCRIPT_DIR}/backups"

# Create backup directory if it doesn't exist
mkdir -p "${BACKUP_DIR}"

# Function to print colored messages
print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to check if database exists
check_database() {
    if [ ! -f "${DB_PATH}" ]; then
        print_error "Database not found at: ${DB_PATH}"
        exit 1
    fi
}

# Function to create backup
create_backup() {
    check_database

    local timestamp=$(date +%Y%m%d_%H%M%S)
    local backup_file="${BACKUP_DIR}/central-index-backup-${timestamp}.sqlite"

    print_info "Creating backup..."
    cp "${DB_PATH}" "${backup_file}"

    if [ $? -eq 0 ]; then
        print_info "Backup created: ${backup_file}"
        echo "${backup_file}"
    else
        print_error "Failed to create backup"
        exit 1
    fi
}

# Function to get current schema version
get_version() {
    check_database

    local version=$(sqlite3 "${DB_PATH}" \
        "SELECT COALESCE(MAX(version), 0) FROM schema_migrations;" 2>/dev/null || echo "0")

    echo "${version}"
}

# Function to check if migration table exists
check_migration_table() {
    local exists=$(sqlite3 "${DB_PATH}" \
        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='schema_migrations';" 2>/dev/null || echo "0")

    [ "${exists}" -eq "1" ]
}

# Function to show migration status
show_status() {
    check_database

    print_info "Database: ${DB_PATH}"

    if check_migration_table; then
        local version=$(get_version)
        print_info "Current schema version: ${version}"

        if [ "${version}" -gt "0" ]; then
            print_info "Applied migrations:"
            sqlite3 "${DB_PATH}" \
                "SELECT version, name, applied_at, description FROM schema_migrations ORDER BY version;"
        else
            print_warn "No migrations applied yet"
        fi
    else
        print_warn "Migration tracking table does not exist (schema v1)"
        print_warn "Current schema version: 1 (original)"
    fi

    # Show table count
    local table_count=$(sqlite3 "${DB_PATH}" "SELECT COUNT(*) FROM sqlite_master WHERE type='table';")
    print_info "Total tables: ${table_count}"

    # Show some data counts
    print_info "Data counts:"
    sqlite3 "${DB_PATH}" "SELECT 'Packages: ' || COUNT(*) FROM Package;"
    sqlite3 "${DB_PATH}" "SELECT 'Functions: ' || COUNT(*) FROM Function;"
    sqlite3 "${DB_PATH}" "SELECT 'Parameters: ' || COUNT(*) FROM Parameter;"

    # Check if new tables exist
    if sqlite3 "${DB_PATH}" "SELECT name FROM sqlite_master WHERE type='table' AND name='TypeDefinition';" | grep -q "TypeDefinition"; then
        sqlite3 "${DB_PATH}" "SELECT 'TypeDefinitions: ' || COUNT(*) FROM TypeDefinition;"
        sqlite3 "${DB_PATH}" "SELECT 'RecordFields: ' || COUNT(*) FROM RecordField;"
        sqlite3 "${DB_PATH}" "SELECT 'EnumMembers: ' || COUNT(*) FROM EnumMember;"
    fi
}

# Function to apply migration
apply_migration() {
    check_database

    local current_version=$(get_version)
    print_info "Current schema version: ${current_version}"

    if [ "${current_version}" -ge "1" ]; then
        print_warn "Migration 001 already applied"
        print_info "Database is up to date"
        return 0
    fi

    # Create backup before migration
    local backup_file=$(create_backup)

    print_info "Applying migration 001: Add type definitions..."

    local migration_file="${MIGRATION_DIR}/001_add_type_definitions.sql"

    if [ ! -f "${migration_file}" ]; then
        print_error "Migration file not found: ${migration_file}"
        exit 1
    fi

    # Apply migration
    if sqlite3 "${DB_PATH}" < "${migration_file}"; then
        print_info "Migration applied successfully!"

        # Verify
        local new_version=$(get_version)
        print_info "New schema version: ${new_version}"

        # Check new tables
        print_info "Verifying new tables..."
        local new_tables=$(sqlite3 "${DB_PATH}" \
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN \
            ('TypeDefinition', 'RecordField', 'EnumMember', 'UnionMember', 'TypeLink', \
             'ClientDefinition', 'ServiceDefinition', 'ClassMethod', 'ClassMethodParameter');")

        if [ "${new_tables}" -eq "9" ]; then
            print_info "All new tables created successfully"
        else
            print_warn "Expected 9 new tables, found ${new_tables}"
        fi

        print_info "Backup saved at: ${backup_file}"
    else
        print_error "Migration failed!"
        print_error "Restoring from backup: ${backup_file}"
        cp "${backup_file}" "${DB_PATH}"
        print_info "Database restored from backup"
        exit 1
    fi
}

# Function to rollback migration
rollback_migration() {
    check_database

    local current_version=$(get_version)
    print_info "Current schema version: ${current_version}"

    if [ "${current_version}" -eq "0" ]; then
        print_warn "No migrations to rollback"
        return 0
    fi

    # Create backup before rollback
    local backup_file=$(create_backup)

    print_warn "Rolling back migration 001: Remove type definitions..."

    local rollback_file="${MIGRATION_DIR}/001_add_type_definitions_rollback.sql"

    if [ ! -f "${rollback_file}" ]; then
        print_error "Rollback file not found: ${rollback_file}"
        exit 1
    fi

    # Apply rollback
    if sqlite3 "${DB_PATH}" < "${rollback_file}"; then
        print_info "Rollback completed successfully!"

        # Verify
        local new_version=$(get_version)
        print_info "Schema version after rollback: ${new_version}"

        print_info "Backup saved at: ${backup_file}"
    else
        print_error "Rollback failed!"
        print_error "Restoring from backup: ${backup_file}"
        cp "${backup_file}" "${DB_PATH}"
        print_info "Database restored from backup"
        exit 1
    fi
}

# Function to list backups
list_backups() {
    print_info "Available backups:"

    if [ -d "${BACKUP_DIR}" ]; then
        local backups=$(ls -1t "${BACKUP_DIR}"/central-index-backup-*.sqlite 2>/dev/null || true)

        if [ -z "${backups}" ]; then
            print_warn "No backups found"
        else
            echo "${backups}" | while read -r backup; do
                local size=$(du -h "${backup}" | cut -f1)
                local date=$(stat -f "%Sm" -t "%Y-%m-%d %H:%M:%S" "${backup}" 2>/dev/null || stat -c "%y" "${backup}" 2>/dev/null | cut -d'.' -f1)
                echo "  - $(basename ${backup}) (${size}, ${date})"
            done
        fi
    else
        print_warn "Backup directory does not exist"
    fi
}

# Function to restore from backup
restore_backup() {
    local backup_file="$1"

    if [ -z "${backup_file}" ]; then
        print_error "Please specify a backup file"
        list_backups
        exit 1
    fi

    if [ ! -f "${backup_file}" ]; then
        print_error "Backup file not found: ${backup_file}"
        exit 1
    fi

    print_warn "Restoring database from: ${backup_file}"
    print_warn "This will overwrite the current database!"

    read -p "Are you sure? (yes/no): " confirm
    if [ "${confirm}" != "yes" ]; then
        print_info "Restore cancelled"
        exit 0
    fi

    cp "${backup_file}" "${DB_PATH}"
    print_info "Database restored successfully"
    show_status
}

# Main script
case "${1:-}" in
    apply)
        print_info "=== Applying Database Migration ==="
        apply_migration
        print_info "=== Migration Complete ==="
        show_status
        ;;
    rollback)
        print_info "=== Rolling Back Database Migration ==="
        rollback_migration
        print_info "=== Rollback Complete ==="
        show_status
        ;;
    status)
        print_info "=== Database Status ==="
        show_status
        ;;
    backup)
        create_backup
        ;;
    list-backups)
        list_backups
        ;;
    restore)
        restore_backup "${2:-}"
        ;;
    *)
        echo "Usage: $0 {apply|rollback|status|backup|list-backups|restore}"
        echo ""
        echo "Commands:"
        echo "  apply         - Apply pending migrations"
        echo "  rollback      - Rollback last migration"
        echo "  status        - Show current database status"
        echo "  backup        - Create a backup of the database"
        echo "  list-backups  - List available backups"
        echo "  restore FILE  - Restore database from backup"
        echo ""
        echo "Examples:"
        echo "  $0 status"
        echo "  $0 backup"
        echo "  $0 apply"
        echo "  $0 rollback"
        echo "  $0 restore backups/central-index-backup-20260106_120000.sqlite"
        exit 1
        ;;
esac
