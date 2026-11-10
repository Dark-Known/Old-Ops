# Installs the daemon service by running install-service.ps1 (elevated, see SettingsPanel.runElevatedPowerShell).
& "{{INSTALL_SCRIPT}}" -InstallDir "{{INSTALL_DIR}}" -DataDir "{{DATA_DIR}}" -WinSwPath "{{WINSW}}" -JarPath "{{JAR}}"
