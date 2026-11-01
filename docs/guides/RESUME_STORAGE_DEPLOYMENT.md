# Resume storage deployment

RecruitPro resolves `app.file-storage.upload-dir` to an absolute, normalized path. The existing `FILE_STORAGE_PATH` environment variable remains the override for this setting. The application validates the configured directory during startup by creating a uniquely named temporary probe file, writing and reading probe bytes, and deleting that same file. Startup fails with a diagnostic error if any required operation fails.

The resolved path is written to application logs for diagnostics. Public health responses do not include the path or other filesystem details.

## Windows security

The application may attempt to set POSIX permissions where the filesystem supports them. A successful or attempted POSIX permission change does not establish Windows security and does not prove that access is restricted. On Windows, NTFS ACLs must be provisioned and verified separately by the deployment administrator for the selected storage directory. Grant only the application identity the access required to create, read, write, and delete resume files, and verify that unrelated identities do not have access.

Before deployment, the operator must select and configure the production storage path, provision the required NTFS ACLs, and verify access using the identity that runs the application. The application does not select a production path or modify ACLs.
