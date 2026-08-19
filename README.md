# Skyipelago

From this folder:

```bash
python3 tools/generate.py
```

**Jar** (JDK 21):

```bash
cd client
cd ..
cp client/build/libs/skyipelago-0.1.0.jar ../modpack/mods/skyipelago-0.1.0.jar
```

**APWorld:**

```bash
rm -f apworld/skyipelago.apworld
( cd apworld/worlds && zip -r ../skyipelago.apworld skyipelago -x '*/__pycache__/*' '*.pyc' )
```

Install `apworld/skyipelago.apworld` in the Archipelago launcher, then generate a new seed.
