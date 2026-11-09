Drop payload files here. Real filenames, any subfolders.

The build seals everything into assets/raw1/p.bin (AES-256-GCM) and the
plaintext never enters the APK. At runtime they are restored byte-for-byte
with their original names, pushed into the selected game's data folder,
and deleted 3 seconds after launch.
