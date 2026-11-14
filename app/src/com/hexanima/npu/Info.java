package com.hexanima.npu;

import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One place for all the "why is it like this" notes that used to clutter the main screen. */
public class Info extends Base {
    static final String[][] ITEMS = {
        {"Pasos y muestreo", "Steps and sampling",
         "Anima guarda los ajustes por modelo. Los modelos turbo parten de 4 pasos, Euler y CFG 1; los modelos normales, de 20 pasos, DPM++ 2M y CFG 4. Puedes cambiarlos en Muestreo; usa los pasos para los que se entrenó el modelo o su LoRA. MOSS usa sus 4 pasos destilados (RealSkin también puede usar el complemento DMD de 8 pasos) y Qwen usa 4 pasos.",
         "Anima keeps sampling settings per model. Turbo models default to 4 steps, Euler and CFG 1; regular models default to 20 steps, DPM++ 2M and CFG 4. Change them under Sampling, and match the steps to the model or LoRA training. MOSS uses its 4 distilled steps (RealSkin can also use the 8-step DMD add-on) and Qwen uses 4 steps."},
        {"Какой размер можно", "Which sizes work",
         "Ширина и высота кратны 16, число патчей (ширина/16 × высота/16) делится на 32 и не больше 6144. Примеры: 512×768, 640×640, 1024×1024, 960×1536. Кнопка ⇄ меняет портрет на альбом. Большие размеры считаются дольше и декодируются плитками.",
         "Width and height are multiples of 16, the patch count (width/16 × height/16) is divisible by 32 and at most 6144. Examples: 512×768, 640×640, 1024×1024, 960×1536. The ⇄ button swaps portrait and landscape. Larger sizes take longer and are decoded in tiles."},
        {"Seed", "Seed",
         "-1 — каждый раз случайный. Число повторяет ту же картинку при том же описании, размере и модели. Использованный seed виден под картинкой и в истории.",
         "-1 is random every time. A number repeats the same picture with the same prompt, size and model. The seed used is shown under the picture and in the history."},
        {"Почему первые шаги быстрее", "Why the first steps are faster",
         "Первые два шага считаются в уменьшенном разрешении (общая композиция), последние два — в полном, поэтому детали сохраняются. На некоторых нестандартных размерах это отключается само.",
         "The first two steps run at reduced resolution (overall composition), the last two at full resolution, so details are kept. On some unusual sizes this switches off automatically."},
        {"Загрузить и выгрузить модель", "Loading and unloading",
         "Загруженная модель занимает несколько гигабайт оперативной памяти. Если телефон тормозит или вы закончили — нажмите «Выгрузить». При генерации модель загрузится сама (~5 с).",
         "A loaded model holds several gigabytes of RAM. If the phone slows down or you are done, tap Unload. It loads itself when you generate (~5 s)."},
        {"Где хранятся картинки", "Where pictures are stored",
         "Только внутри приложения. В галерею телефона (Pictures/Anima) картинка попадает после «Скачать». Удалённое уходит в корзину и хранится 30 дней.",
         "Only inside the app. A picture goes to the phone gallery (Pictures/Anima) after Download. Deleted pictures go to the trash and stay there for 30 days."},
        {"Негативный промпт и CFG", "Negative prompt and CFG",
         "Turbo-модели рассчитаны на CFG 1: негативный промпт при этом ничего не меняет. Обычные модели используют CFG 3-5 и негативный промпт (Сэмплинг под описанием); картинка тогда считается примерно вдвое дольше.",
         "Turbo models are made for CFG 1, where a negative prompt changes nothing. Non-turbo models use CFG 3-5 and the negative prompt (Sampling, under the prompt); each picture then takes about twice as long."},
        {"Теги качества и подсказки", "Quality tags and suggestions",
         "Перед описанием автоматически добавляются теги качества (их можно изменить в настройках). В поле описания при вводе появляются подсказки тегов Danbooru, а кнопка «Шаблоны» хранит сохранённые и недавние описания.",
         "Quality tags are added before your prompt automatically (change them in Settings). While you type, Danbooru tag suggestions appear, and the Templates button keeps saved and recent prompts."},
        {"Теги качества: только Anima", "Quality tags: Anima only",
         "Теги качества из настроек добавляются только к описаниям для моделей Anima. MOSS-Image их не получает, а пока она выбрана, этой настройки в списке нет.",
         "The quality tags from Settings are added only to prompts for Anima models. MOSS-Image does not get them, and the setting is hidden while MOSS-Image is selected."},
        {"MOSS-Image", "MOSS-Image",
         "MOSS-Image 5B (Apache-2.0) — вторая, более крупная модель: описывайте картинку обычными предложениями, а не тегами. Текстовый энкодер, трансформер и декодер работают на NPU. Нужно около 14 ГБ на накопителе; проверено на телефоне с 12 ГБ ОЗУ. Веса читаются с накопителя при каждой картинке: около 30 с для 512×768 и около 80 с для 1 Мпикс. Загружать модель заранее не нужно.",
         "MOSS-Image 5B (Apache-2.0) is a second, larger model: describe pictures in plain sentences, not tags. The text encoder, the transformer and the decoder all run on the NPU. It needs about 14 GB of storage and was tested on a phone with 12 GB of RAM. Its weights are streamed from storage for every picture: about 30 s for 512×768, about 80 s for 1 Mpx. There is nothing to preload."},
        {"Размеры MOSS-Image", "MOSS-Image sizes",
         "Подходит любой размер: обе стороны кратны 16, не меньше 256, не больше 6144 патчей (ширина/16 × высота/16), например 1536×1024. 4-шаговый адаптер обучали примерно на 1 Мпикс (1024×1024, 832×1216…), но на меньших размерах картинки тоже получаются. Размеры без готового декодера декодируются плитками, поэтому тон может слегка сместиться.",
         "Any size works: both sides are multiples of 16, at least 256, at most 6144 patches (width/16 × height/16), for example 1536×1024. The 4-step adapter was trained at about 1 Mpx (1024×1024, 832×1216…), but smaller sizes also work. Sizes without a ready decoder are decoded in tiles, so the tone can shift slightly."},
        {"LoRA для MOSS-Image", "LoRA for MOSS-Image",
         "Выберите свой файл .safetensors, сделанный именно для MOSS-Image (LoRA для FLUX, SDXL и других моделей не подходят). Файл проверяется сразу после выбора, а если он не подходит, приложение объяснит почему. Сила 1,00 — как задумал автор: меньше — слабее эффект, больше — сильнее. Первая картинка с новой LoRA или новой силой тратит около 15 с на подготовку (временные файлы около 300 МБ). Использование LoRA регулируется лицензией её автора.",
         "Pick your own .safetensors file made for MOSS-Image (LoRAs for FLUX, SDXL and other models do not fit). It is checked as soon as you pick it, and if it does not fit the app says why. Strength 1.00 is the author's setting: lower is weaker, higher is stronger. The first picture with a new LoRA or strength spends about 15 s preparing it (temporary files of about 300 MB). Full 4×4 LoKr adapters also work: the first picture at each strength prepares a copy of the weights (about 20 s and 5.7 GB; only the last one is kept). Using a LoRA is subject to its author's license."},
        {"Qwen-Image 2.1", "Qwen-Image 2.1",
         "Qwen-Image 2.1 Turbo (команда Qwen) с 4-шаговым адаптером Lightning (LightX2V) — модель, которая хорошо рисует надписи и понимает длинные описания обычными предложениями. Текст, ViT, DiT и VAE работают на NPU; нужно около 14 ГБ памяти, рекомендуется телефон с 12 ГБ ОЗУ. Только некоммерческое использование (Qwen Research License).",
         "Qwen-Image 2.1 Turbo (Qwen team) with the 4-step Lightning adapter (LightX2V): draws legible text in pictures and follows long prompts written as plain sentences. The text encoder, the ViT, the transformer and the VAE all run on the NPU; it needs about 14 GB of storage and a phone with 12 GB of RAM is recommended. Non-commercial use only (Qwen Research License)."},
        {"LoRAs de Qwen", "Qwen LoRAs",
         "Importa una LoRA .safetensors de Qwen-Image 2.1 (también desde Civitai): se convierte en el teléfono en unos segundos. El deslizador de intensidad (o tocar el número para escribirlo, de 0 a 2) la vuelve a convertir y reemplaza la copia anterior. Mantén pulsada una LoRA para copiar, editar o añadir sus palabras activadoras, o para eliminarla.",
         "Import a Qwen-Image 2.1 .safetensors LoRA (also from Civitai): it is converted on the phone in a few seconds. The strength slider (or tap the number to type it, 0 to 2) converts it again and replaces the previous copy. Long-press a LoRA to copy, edit or add its trigger words, or to delete it."},
        {"Редактирование фото", "Editing photos",
         "Para Anima: abra «EXTRAS» → «IMAGEN INICIAL (img2img)» → «Elegir» y seleccione una foto. Escriba solo el cambio que desea, por ejemplo «conserva la misma persona y composición; cambia únicamente el cielo a un atardecer». Empiece con fuerza 0,25–0,40 para conservar más la foto; 0,6 permite más cambios y puede alterar identidad, pose y fondo. La imagen se ajusta al tamaño elegido y puede recortarse por el centro. Para cambiar una zona, abra «Máscara» y pinte sobre esa zona; lo pintado se vuelve a generar. «Última imagen» permite usar un resultado reciente. Qwen: EDITAR UNA FOTO → Elegir. Por defecto la edición conserva la forma de la foto (interruptor «Forma de la foto»); «Área a cambiar» limita la edición a la zona pintada con bordes suaves, y el deslizador antes/después compara el resultado. MOSS solo genera desde texto.",
         "For Anima: open EXTRAS → INIT IMAGE (img2img) → Choose and pick a photo. Describe only the change you want, for example, \"keep the same person and composition; change only the sky to sunset\". Start with strength 0.25–0.40 to preserve more of the photo; 0.6 allows larger changes and may alter identity, pose and background. The image is fitted to the chosen size and may be center-cropped. To change one area, open Mask and paint over it; the painted area is regenerated. Last picture lets you reuse a recent result. Qwen: EDIT A PHOTO → Choose. By default the edit keeps the photo's shape (switch \"Photo's shape\"); Area to change limits the edit to a painted area with soft edges, and the before/after slider compares the result. MOSS only generates from text."},
        {"Размеры Qwen-Image", "Qwen-Image sizes",
         "Обе стороны кратны 16, число патчей (ширина/16 × высота/16) делится на 32 и не больше 4608, например 1024×1024 или 768×1152. При редактировании фото и результат вместе не должны превышать примерно 6800 патчей.",
         "Both sides are multiples of 16, the patch count (width/16 × height/16) is divisible by 32 and at most 4608, for example 1024×1024 or 768×1152. When editing, the photo and the result together must stay within about 6800 patches."},
        {"Какие телефоны подходят", "Supported phones",
         "Проверено на Snapdragon 8 Gen 1 / 8+ Gen 1 (Hexagon V69) и Snapdragon 8 Gen 2 (Hexagon V73, Poco F6 Pro). На V73 MOSS-Image экспериментальный: проверена генерация 512×512, но не длительные серии и не все размеры. Root не нужен. Остальные чипы не поддерживаются.",
         "Tested on Snapdragon 8 Gen 1 / 8+ Gen 1 (Hexagon V69) and Snapdragon 8 Gen 2 (Hexagon V73, Poco F6 Pro). On V73, MOSS-Image was verified at 512×768 and 1024×768 (also with LoKr) and Qwen-Image for generation and editing; long batches were not tested. No root needed. Other chips are not supported."},
        {"Лицензии", "Licenses",
         "Модели MiaoMiao (автор MIAOKA, Civitai) построены на Anima от CircleStone Labs и NVIDIA Cosmos. Только некоммерческое использование. Автор модели может потребовать убрать её из каталога.",
         "MiaoMiao models (by MIAOKA, Civitai) are built on Anima by CircleStone Labs and NVIDIA Cosmos. Non-commercial use only. A model author can ask for removal from the catalog."},
        {"Лицензии MOSS-Image", "MOSS-Image licenses",
         "MOSS-Image (OpenMOSS), Qwen3.5-9B (команда Qwen), адаптер rapid-moss (darask0, lodestones), малый декодер FLUX.2 (Black Forest Labs) и квантизации Unsloth — все под Apache License 2.0. Файлы — конвертированные для NPU копии; изменения перечислены в уведомлениях ниже. Тексты лицензий внизу, в «Лицензиях компонентов».",
         "MOSS-Image (OpenMOSS), Qwen3.5-9B (Qwen team), the rapid-moss adapter (darask0, lodestones), the FLUX.2 small decoder (Black Forest Labs) and the Unsloth quantisations are all under the Apache License 2.0. The files are converted copies made for the NPU; the changes are listed in the notices below. License texts are at the bottom, under Component licenses."},
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = screen(L.s("Инфо", "Info"), null);
        for (String[] it : ITEMS) {
            LinearLayout c = card(col);
            c.addView(text(L.s(it[0], it[1]), 16, TXT, true));
            TextView body = text(L.s(it[2], it[3]), 14, DIM, false); body.setPadding(0, dp(6), 0, 0); body.setLineSpacing(0, 1.15f);
            c.addView(body);
        }
        about(col);
    }

    void about(LinearLayout col) {
        LinearLayout c = card(col);
        String ver = "";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        c.addView(text(L.s("О приложении", "About"), 16, TXT, true));
        TextView v = text("HexAnima " + ver + "\n" + L.f("Чип %s · Android %s", "Chip %s · Android %s", Compat.soc(), android.os.Build.VERSION.RELEASE), 13, DIM, false); v.setPadding(0, dp(6), 0, dp(8)); c.addView(v);
        Button log = button(L.s("Отправить лог", "Send the log"), FIELD, ACC); log.setTextSize(13);
        log.setOnClickListener(x -> sendLog());
        c.addView(log, new LinearLayout.LayoutParams(-1, dp(42)));
        TextView cr = text(L.s("HexAnima создал ayanoby: NPU-ядра, сервер, приложение и модели для NPU — всё основное это его работа. Эта сборка — форк для Snapdragon 8 Gen 2 (GrimmDev-Glitch) с LoRA, Civitai и другими дополнениями. Спасибо, ayanoby!",
            "HexAnima was created by ayanoby: the NPU kernels, the server, the app and the NPU model packs are their work. This build is a fork for the Snapdragon 8 Gen 2 (GrimmDev-Glitch) with LoRAs, Civitai and other additions. Thank you, ayanoby!"), 13, TXT, false);
        cr.setPadding(0, dp(4), 0, dp(10)); cr.setLineSpacing(0, 1.15f); c.addView(cr, 3);
        TextView lh = text(L.s("Авторы и ссылки", "Authors and links"), 14, TXT, true); lh.setPadding(0, dp(14), 0, dp(2)); c.addView(lh);
        String[][] links = {
            {"HexAnima — ayanoby (original author)", "https://github.com/ayanoby/hexanima"},
            {"Snapdragon 8 Gen 2 fork — GrimmDev-Glitch", "https://github.com/GrimmDev-Glitch/hexanima"},
            {"MiaoMiao 3D Harem (MIAOKA)", "https://civitai.red/models/431957/miaomiao-3d-harem"},
            {"MiaoMiao Harem (MIAOKA)", "https://civitai.red/models/934764/miaomiao-harem"},
            {"MiaoMiao RealSkin (MIAOKA)", "https://civitai.red/models/2026594/miaomiao-realskin"},
            {"Anima (CircleStone Labs)", "https://huggingface.co/circlestone-labs/Anima"},
            {"MOSS-Image-SFT (OpenMOSS)", "https://huggingface.co/OpenMOSS-Team/MOSS-Image-SFT"},
            {"MOSS-Image 4-step adapter (darask0)", "https://huggingface.co/darask0/MOSS-Image_4-step_adapter_lora"},
            {"MOSS-Image 8-step DMD (lodestones)", "https://huggingface.co/lodestones/MOSS-Image-SFT-8-steps-dmd"},
            {"Qwen3.5-9B (Qwen team)", "https://huggingface.co/Qwen/Qwen3.5-9B"},
            {"Unsloth GGUF quantisations", "https://huggingface.co/unsloth/MOSS-Image-SFT-GGUF"},
            {"FLUX.2 small decoder (Black Forest Labs)", "https://huggingface.co/black-forest-labs/FLUX.2-small-decoder"},
        };
        for (String[] l : links) {
            TextView t = text(l[0], 13, ACC, false); t.setPadding(0, dp(8), 0, 0);
            t.setOnClickListener(x -> startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(l[1]))));
            c.addView(t);
        }
        TextView hh = text(L.s("Лицензии компонентов", "Component licenses"), 14, TXT, true); hh.setPadding(0, dp(14), 0, dp(2)); c.addView(hh);
        String[][] lic = {
            {"Anima — CircleStone Labs Non-Commercial License; NVIDIA Open Model License (Cosmos)", ""},
            {"MiaoMiao models — by MIAOKA, non-commercial use, see the model pages", ""},
            {"Turbo distillation LoRAs merged into the models — Anima Turbo v1.1 Lora by somedoby (2B) and TURBO for ANIMA V4-29B by n_Arno (2.9B), both on Civitai. The models are modified versions of Anima (CircleStone Labs Non-Commercial License), not an official CircleStone Labs product", ""},
            {"Qwen3 0.6B text encoder, Qwen-Image VAE — Apache 2.0", ""},
            {"MOSS-Image-SFT (OpenMOSS), rapid-moss adapter (darask0) and its 8-step DMD base (lodestones), Qwen3.5-9B (Qwen team), Unsloth GGUF quantisations, FLUX.2 small decoder (Black Forest Labs) — all Apache License 2.0. The MOSS-Image pack holds converted copies (re-packed for the NPU, the adapter kept unmerged); user LoRAs stay under their authors' licenses", ""},
            {"Qwen-Image-2.1-Turbo, its VAE and the Lightning v2-RL adapter (LightX2V) — Qwen RESEARCH LICENSE AGREEMENT, non-commercial use only; Qwen3-VL-8B-Instruct and its vision tower, Unsloth GGUF quantisations — Apache License 2.0. The Qwen-Image pack holds converted copies (re-packed for the NPU, the adapter kept unmerged)", ""},
            {"stable-diffusion.cpp — MIT", ""},
            {"TAEHV preview decoder — see github.com/madebyollin/taehv", ""},
            {"Qualcomm AI Engine Direct (QNN) runtime libraries — Qualcomm AI Stack License (redistributed only inside this app)", ""},
            {"Tag suggestions — Danbooru tag list from DominikDoom/a1111-sd-webui-tagcomplete", ""},
        };
        for (String[] l : lic) { TextView t = text("•  " + l[0], 12, DIM, false); t.setPadding(0, dp(6), 0, 0); c.addView(t); }
        for (String[] q : new String[][]{{"Qualcomm AI Stack License", "licenses/qualcomm_ai_stack_license.txt"}, {L.s("Уведомления QNN (сторонние компоненты)", "QNN notices (third-party components)"), "licenses/qnn_notice.txt"}, {"Apache License 2.0", "licenses/apache-2.0.txt"}, {"Qwen RESEARCH LICENSE AGREEMENT", "licenses/qwen_research_license.txt"}, {L.s("Уведомления Qwen-Image (изменения)", "Qwen-Image notices (changes made)"), "licenses/qwen_notice.txt"}, {L.s("Уведомления MOSS-Image (изменения)", "MOSS-Image notices (changes made)"), "licenses/moss_notice.txt"}, {L.s("Шрифт Barlow Semi Condensed (SIL OFL 1.1)", "Barlow Semi Condensed font (SIL OFL 1.1)"), "licenses/barlow_ofl.txt"}}) {
            TextView t = text(q[0], 13, ACC, false); t.setPadding(0, dp(8), 0, 0);
            t.setOnClickListener(x -> showAsset(q[0], q[1]));
            c.addView(t);
        }
    }

    void showAsset(String title, String path) {
        String body;
        try (java.io.InputStream in = getAssets().open(path)) { body = new String(in.readAllBytes(), "UTF-8"); } catch (Exception e) { body = "(none)"; }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        TextView t = text(body, 11, DIM, false); t.setPadding(dp(18), dp(14), dp(18), dp(14)); t.setTextIsSelectable(true);
        sv.addView(t);
        builder().setTitle(title).setView(sv).setPositiveButton(L.s("Закрыть", "Close"), null).show();
    }

    /** share the device info and the tails of the engine logs as plain text */
    void sendLog() {
        StringBuilder sb = new StringBuilder();
        try { sb.append("HexAnima ").append(getPackageManager().getPackageInfo(getPackageName(), 0).versionName); } catch (Exception ignored) { }
        sb.append("\nChip ").append(Compat.soc()).append(", Android ").append(android.os.Build.VERSION.RELEASE).append(", ").append(android.os.Build.MODEL)
          .append(String.format(", RAM %.1f GB, free %.1f GB\n", Compat.ramGb(this), Compat.freeGb(this)));
        sb.append("Installed: ").append(new Models(getFilesDir()).installed()).append("\n");
        for (String n : new String[]{"animad.log", "sdcli.log", "gen.log"}) sb.append("\n==== ").append(n).append(" ====\n").append(tail(new java.io.File(getFilesDir(), "anima/" + n), 12000));
        android.content.Intent s = new android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_SUBJECT, "HexAnima log").putExtra(android.content.Intent.EXTRA_TEXT, sb.toString());
        startActivity(android.content.Intent.createChooser(s, L.s("Отправить лог", "Send the log")));
    }

    static String tail(java.io.File f, int max) {
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
            long from = Math.max(0, r.length() - max); r.seek(from);
            byte[] b = new byte[(int) (r.length() - from)]; r.readFully(b);
            return new String(b, "UTF-8");
        } catch (Exception e) { return "(none)"; }
    }
}
