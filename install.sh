#!/bin/bash
set -e

INSTALL_DIR="$HOME/.aegis/bin"
echo "Installing AegisOS to $INSTALL_DIR..."

mkdir -p "$INSTALL_DIR"

if [ ! -f "./aegis-cli/target/aegis.jar" ]; then
    echo "Error: aegis.jar not found. Please compile the project first."
    exit 1
fi

cp "./aegis-cli/target/aegis.jar" "$INSTALL_DIR/aegis.jar"

# Create the bash executable wrapper
cat << 'EOF' > "$INSTALL_DIR/aegis"
#!/bin/bash
exec java -jar "$HOME/.aegis/bin/aegis.jar" "$@"
EOF

chmod +x "$INSTALL_DIR/aegis"

# Detect shell profile
PROFILE="/dev/null"
if [ -f "$HOME/.bashrc" ]; then PROFILE="$HOME/.bashrc"; fi
if [ -f "$HOME/.zshrc" ]; then PROFILE="$HOME/.zshrc"; fi

if ! grep -q "$INSTALL_DIR" "$PROFILE"; then
    echo "export PATH=\"\$PATH:$INSTALL_DIR\"" >> "$PROFILE"
    echo "Successfully added to PATH in $PROFILE."
fi

echo -e "\n========================================="
echo "AEGIS OS INSTALLED SUCCESSFULLY"
echo "========================================="
echo "Please restart your terminal or run: source $PROFILE"