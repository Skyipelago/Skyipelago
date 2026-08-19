# Skyipelago

From this folder:

```bash
python3 tools/generate.py
```

**Jar** (JDK 21):

```bash
cd client
./gradlew build
cd ..
```
copy the .jar file at client/build/libs/ into the Modpack

**APWorld:**

```bash
rm -f apworld/skyipelago.apworld
( cd apworld/worlds && zip -r ../skyipelago.apworld skyipelago -x '*/__pycache__/*' '*.pyc' )
```

Install `apworld/skyipelago.apworld` in the Archipelago launcher, then generate a new seed.
