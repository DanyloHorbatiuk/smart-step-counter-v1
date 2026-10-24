"""Trains the activity classifier and exports it for Android (Lab 6).

Usage:  ml/.venv/bin/python ml/train.py   (after generate_dataset.py)
Output: app/src/main/assets/activity_classifier.tflite
        app/src/main/assets/labels.txt
        ml/report.md

Architecture: Dense 32 ReLU -> Dense 16 ReLU -> Dense 4 Softmax on a 1 s window.
If its test accuracy is below 95 % the script falls back to a small Conv1D network
and records why in the report.
"""

import io
import json
import os
import tempfile
from pathlib import Path

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import numpy as np  # noqa: E402
import tensorflow as tf  # noqa: E402
from sklearn.metrics import classification_report, confusion_matrix  # noqa: E402

ML_DIR = Path(__file__).resolve().parent
ROOT = ML_DIR.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets"
MODEL_FILE = ASSETS / "activity_classifier.tflite"
LABELS_FILE = ASSETS / "labels.txt"
REPORT_FILE = ML_DIR / "report.md"

CONFIG = json.loads((ML_DIR / "model_config.json").read_text())
WINDOW = CONFIG["window"]
LABELS = CONFIG["labels"]
# Normalisation constant shared with MlConfig.INPUT_SCALE (checked by MlConfigTest).
INPUT_SCALE = CONFIG["input_scale"]

SEED = 42
TARGET_ACCURACY = 0.95


def build_dense() -> tf.keras.Model:
    return tf.keras.Sequential([
        tf.keras.Input(shape=(WINDOW,), name="window"),
        tf.keras.layers.Dense(32, activation="relu"),
        tf.keras.layers.Dense(16, activation="relu"),
        tf.keras.layers.Dense(len(LABELS), activation="softmax"),
    ], name="dense_classifier")


def build_conv() -> tf.keras.Model:
    return tf.keras.Sequential([
        tf.keras.Input(shape=(WINDOW,), name="window"),
        tf.keras.layers.Reshape((WINDOW, 1)),
        tf.keras.layers.Conv1D(8, 5, activation="relu"),
        tf.keras.layers.MaxPooling1D(2),
        tf.keras.layers.Conv1D(16, 5, activation="relu"),
        tf.keras.layers.GlobalAveragePooling1D(),
        tf.keras.layers.Dense(16, activation="relu"),
        tf.keras.layers.Dense(len(LABELS), activation="softmax"),
    ], name="conv1d_classifier")


def fit(model, data):
    model.compile(optimizer=tf.keras.optimizers.Adam(1e-3),
                  loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    model.fit(data["x_train"], data["y_train"],
              validation_data=(data["x_val"], data["y_val"]),
              epochs=60, batch_size=64, verbose=2,
              callbacks=[tf.keras.callbacks.EarlyStopping(
                  monitor="val_accuracy", patience=8, restore_best_weights=True)])
    _, acc = model.evaluate(data["x_test"], data["y_test"], verbose=0)
    return acc


def export_tflite(model) -> bytes:
    # Keras 3 models are converted through a SavedModel; converting a tf.function that
    # captures Keras variables directly fails in TF 2.17 ("missing attribute 'value'").
    # Fixed batch of 1: the app classifies one window at a time -> input [1, 50].
    with tempfile.TemporaryDirectory() as tmp:
        archive = tf.keras.export.ExportArchive()
        archive.track(model)
        archive.add_endpoint(
            "serving_default",
            lambda window: model(window, training=False),
            input_signature=[tf.TensorSpec([1, WINDOW], tf.float32, name="window")],
        )
        archive.write_out(tmp, verbose=False)
        return tf.lite.TFLiteConverter.from_saved_model(tmp).convert()


def tflite_predict(model_bytes: bytes, x: np.ndarray):
    interpreter = tf.lite.Interpreter(model_content=model_bytes)
    interpreter.allocate_tensors()
    inp, out = interpreter.get_input_details()[0], interpreter.get_output_details()[0]
    probs = np.empty((len(x), len(LABELS)), dtype=np.float32)
    for i, row in enumerate(x):
        interpreter.set_tensor(inp["index"], row[None, :])
        interpreter.invoke()
        probs[i] = interpreter.get_tensor(out["index"])[0]
    return probs, inp, out


def summary_text(model) -> str:
    buf = io.StringIO()
    model.summary(print_fn=lambda line, **_: buf.write(line + "\n"))
    return buf.getvalue()


def main():
    tf.keras.utils.set_random_seed(SEED)
    raw = np.load(ML_DIR / "data" / "dataset.npz")
    data = {k: (raw[k] / INPUT_SCALE).astype(np.float32) if k.startswith("x_") else raw[k]
            for k in raw.files}

    model = build_dense()
    dense_acc = fit(model, data)
    print(f"Dense test accuracy: {dense_acc:.4f}")
    if dense_acc >= TARGET_ACCURACY:
        choice = (f"Dense-мережа дала {dense_acc:.2%} на test (≥ {TARGET_ACCURACY:.0%}), тому "
                  "Conv1D не потрібна: Dense менша, швидша і простіша для TFLite.")
    else:
        conv = build_conv()
        conv_acc = fit(conv, data)
        print(f"Conv1D test accuracy: {conv_acc:.4f}")
        if conv_acc > dense_acc:
            model = conv
        choice = (f"Dense-мережа дала лише {dense_acc:.2%} (< {TARGET_ACCURACY:.0%}), тому навчено "
                  f"Conv1D ({conv_acc:.2%}): згортки бачать локальну форму і частоту коливань "
                  f"незалежно від фази. Обрано: {model.name}.")

    model_bytes = export_tflite(model)
    ASSETS.mkdir(parents=True, exist_ok=True)
    MODEL_FILE.write_bytes(model_bytes)
    LABELS_FILE.write_text("\n".join(LABELS) + "\n")

    # Metrics are computed on the exported .tflite, i.e. on what really runs on the phone.
    probs, inp, out = tflite_predict(model_bytes, data["x_test"])
    y_true, y_pred = data["y_test"], probs.argmax(axis=1)
    accuracy = float((y_true == y_pred).mean())
    cm = confusion_matrix(y_true, y_pred, labels=range(len(LABELS)))
    cls = classification_report(y_true, y_pred, target_names=LABELS, digits=3, output_dict=True)
    print(f"TFLite test accuracy: {accuracy:.4f}, size {len(model_bytes)} B")

    cm_rows = "\n".join(f"| **{LABELS[i]}** | " + " | ".join(str(v) for v in row) + " |"
                        for i, row in enumerate(cm))
    cls_rows = "\n".join(
        f"| {name} | {cls[name]['precision']:.3f} | {cls[name]['recall']:.3f} | "
        f"{cls[name]['f1-score']:.3f} | {int(cls[name]['support'])} |" for name in LABELS)

    REPORT_FILE.write_text(f"""# Activity classifier: звіт навчання

Згенеровано `ml/train.py` (seed {SEED}). Дані: `ml/generate_dataset.py`,
train/val/test = {len(data['y_train'])}/{len(data['y_val'])}/{len(data['y_test'])} вікон.

## Архітектура

{choice}

```
{summary_text(model).strip()}
```

- Параметрів: **{model.count_params()}**
- Розмір `activity_classifier.tflite`: **{len(model_bytes)} B ({len(model_bytes) / 1024:.1f} KiB)**, float32, без квантизації

## Тензори

| | Ім'я | Форма | dtype | Зміст |
|---|---|---|---|---|
| Вхід | `{inp['name']}` | {list(inp['shape'])} | {np.dtype(inp['dtype']).name} | {WINDOW} відліків динамічного прискорення (\\|a\\| − g після фільтрів StepDetector), 50 Гц = 1 с, поділених на **{INPUT_SCALE} м/с²** |
| Вихід | `{out['name']}` | {list(out['shape'])} | {np.dtype(out['dtype']).name} | softmax-ймовірності в порядку `labels.txt`: {", ".join(f"{i}={l}" for i, l in enumerate(LABELS))} |

## Метрики (test, виміряно на .tflite)

**Accuracy: {accuracy:.2%}**

> Дані синтетичні й класи добре розділені за частотою та амплітудою, тому ця оцінка
> оптимістична. На реальних записах (телефон у кишені, сходи, транспорт) точність буде нижчою.

Матриця помилок (рядки — істинний клас, стовпці — прогноз):

| | {" | ".join(LABELS)} |
|---|{"---|" * len(LABELS)}
{cm_rows}

| Клас | Precision | Recall | F1 | Support |
|---|---|---|---|---|
{cls_rows}
""")
    print(f"wrote {MODEL_FILE.relative_to(ROOT)}, {LABELS_FILE.relative_to(ROOT)}, "
          f"{REPORT_FILE.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
