#!/bin/bash

# =========================
# CLEAN GIT + SSH SETUP TERMUX
# =========================

# 1. Hapus semua SSH key lama
echo "[*] Hapus semua SSH key lama..."
rm -f ~/.ssh/id_*
rm -f ~/.ssh/known_hosts

# 2. Generate SSH key baru
echo "[*] Generate SSH key baru untuk akun GitHub..."
read -p "Masukkan email GitHub: " GITHUB_EMAIL
ssh-keygen -t ed25519 -C "$GITHUB_EMAIL" -f ~/.ssh/id_ed25519 -N ""

# 3. Buat config SSH dengan alias
echo "[*] Buat config SSH..."
cat > ~/.ssh/config <<EOL
Host github-akun
  HostName github.com
  User git
  IdentityFile ~/.ssh/id_ed25519
EOL
chmod 600 ~/.ssh/config

# 4. Tampilkan public key untuk ditambahkan ke GitHub
echo "[*] Public key baru, copy ke GitHub -> Settings -> SSH and GPG keys -> New SSH key:"
cat ~/.ssh/id_ed25519.pub

echo ""
echo "=== Tunggu sampai key ditambahkan ke GitHub, lalu tekan ENTER ==="
read

# 5. Set remote SSH pakai alias
echo "[*] Set remote repo ke SSH alias..."
read -p "Masukkan remote repo SSH (format: git@github-akun:username/repo.git): " REPO_SSH
git remote set-url origin $REPO_SSH

# 6. Push commit terakhir
echo "[*] Push commit ke remote..."
git push origin master

echo "[*] Selesai! Repo sudah terpush pakai SSH key baru."
