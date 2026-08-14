## Morganite 0.0.4

- Verify blob hashes during download in a single pass before caching
- Track cache size incrementally to avoid directory rescans on every blob save
- Increase buffer sizes and move blocking network calls to I/O threads for better performance
- Reuse Tika instances for faster MIME-type detection
- Persist logs to a local Room database instead of logcat
- Expose logs as StateFlow for the log viewer
- Remove permission result logging in MainActivity
- Update deployment target selection state in IDE configuration

Download it with [Zapstore](https://zapstore.dev/apps/com.greenart7c3.morganite), [Obtainium](https://github.com/ImranR98/Obtainium) or download it directly in the [releases page](https://github.com/greenart7c3/Morganite/releases/tag/v0.0.4)

### Verifying the release

In order to verify the release, you'll need to have `gpg` or `gpg2` installed on your system. Once you've obtained a copy (and hopefully verified that as well), you'll first need to import the keys that have signed this release if you haven't done so already:

``` bash
gpg --keyserver hkps://keys.openpgp.org --recv-keys 44F0AAEB77F373747E3D5444885822EED3A26A6D
```

Once you have his PGP key you can verify the release (assuming `manifest-v0.0.4.txt` and `manifest-v0.0.4.txt.sig` are in the current directory) with:

``` bash
gpg --verify manifest-v0.0.4.txt.sig manifest-v0.0.4.txt
```

That will verify the signature on the main manifest page which ensures integrity and authenticity of the binaries you've downloaded locally. Next, depending on your operating system you should then re-calculate the sha256 sum of the binary, and compare that with the following hashes:

``` bash
cat manifest-v0.0.4.txt
```

## Morganite 0.0.3

- Start Tor on demand and stop it when idle to save battery
- Disconnect the nostr relay after author lookup to stop background drain
- Fix battery drain from an unfiltered logcat stream and leaked HTTP clients
- Release replaced OkHttp clients off the main thread
- Fetch the user's inbox relays before querying the blossom server list
- Download the blob on HEAD requests when it is not cached locally
- Add a clear storage button with a confirmation dialog
- Restore the client-side log filter so logs show again
- Update receiver registration to use ContextCompat

Download it with [Zapstore](https://zapstore.dev/apps/com.greenart7c3.morganite), [Obtainium](https://github.com/ImranR98/Obtainium) or download it directly in the [releases page](https://github.com/greenart7c3/Morganite/releases/tag/v0.0.3)

If you like my work consider making a [donation](https://greenart7c3.com)

### Verifying the release

In order to verify the release, you'll need to have `gpg` or `gpg2` installed on your system. Once you've obtained a copy (and hopefully verified that as well), you'll first need to import the keys that have signed this release if you haven't done so already:

``` bash
gpg --keyserver hkps://keys.openpgp.org --recv-keys 44F0AAEB77F373747E3D5444885822EED3A26A6D
```

Once you have his PGP key you can verify the release (assuming `manifest-v0.0.3.txt` and `manifest-v0.0.3.txt.sig` are in the current directory) with:

``` bash
gpg --verify manifest-v0.0.3.txt.sig manifest-v0.0.3.txt
```

You should see the following if the verification was successful:

``` bash
gpg: Signature made Fri 13 Sep 2024 08:06:52 AM -03
gpg:                using RSA key 44F0AAEB77F373747E3D5444885822EED3A26A6D
gpg: Good signature from "greenart7c3 <greenart7c3@proton.me>"
```

That will verify the signature on the main manifest page which ensures integrity and authenticity of the binaries you've downloaded locally. Next, depending on your operating system you should then re-calculate the sha256 sum of the binary, and compare that with the following hashes:

``` bash
cat manifest-v0.0.3.txt
```
