# ROYAL — اللي اتغيّر عن VELORA (V11 → V17)

للمطوّر اللي هيبني الواجهة (Angular). ده **مش نسخة من العقد** — ده دليل "إيه اللي اتغيّر وإيه اللي يتوقعه".
التفاصيل الكاملة لكل endpoint في [VELORA_API_Contract.md](VELORA_API_Contract.md).

**اقرأ القسم ٣ الأول.** الحقول الجديدة سهلة؛ اللي اتغيّر في سلوك قائم هو اللي بيكسر الواجهة من غير ما حد يفهم ليه.

كل المبالغ شاملة الضريبة وبتتحسب في السيرفر. الواجهة تعرض ولا تحسب.

---

## ١. الحقول الجديدة

"إجباري" معناها إجباري **في الطلب اللي الواجهة بتبعته**. الحقول اللي في الردود بتيجي دايمًا (ممكن `null`).

### المنتج

| الحقل | النوع | إجباري؟ | بيظهر في |
|---|---|---|---|
| `fulfillmentType` | `READY_MADE` \| `MADE_TO_ORDER` \| `CUSTOM_WORK` | لأ — الافتراضي `READY_MADE` | تفاصيل المنتج، كارت المنتج، رد الأدمن، وفلتر `?fulfillmentType=` |
| `shippingSizeClass` | `SMALL` \| `MEDIUM` \| `LARGE` | **إجباري لو `READY_MADE`**، وإلا اختياري | نفس الأماكن |
| `requiresAssembly` | boolean | لأ — الافتراضي `false` | نفس الأماكن |
| `assemblyFee` | رقم ≥ 0 | لأ — الافتراضي `0` | نفس الأماكن |

- `fulfillmentType` معناه **إزاي المنتج بيتباع**، مش نوعه. ساعة/محفظة/عطر يفضلوا هما الـ category.
- `assemblyFee` سعر **القطعة الواحدة** شامل الضريبة، ومش بيتحسب غير لو `requiresAssembly=true`. في الرد العام (`/products`) القيمة الفعّالة (صفر لو التركيب مش مطلوب). في رد الأدمن القيمة المخزّنة، عشان نموذج التعديل يرجّعها زي ما هي.
- `PUT /admin/products/{id}`: أي حقل من الأربعة **لو ما اتبعتش يفضل زي ما هو**. مفيش طريقة لمسحه بـ `null`؛ لإلغاء التركيب ابعت `requiresAssembly:false` و`assemblyFee:0`.
- كل المنتجات القديمة بقت `READY_MADE` و**`shippingSizeClass = null`** (مفيش مقاس مسجّل ومش هنخمّن). لازم الأدمن يحدد مقاس لكل منتج (انظر ٣-٥).

### الطلب (`OrderResponse`، للعميل وللأدمن)

| الحقل | النوع | ملاحظة |
|---|---|---|
| `shippingBreakdown` | مصفوفة `{sizeClass, quantity, unitCost, lineCost}` | إزاي اتحسب الشحن. `null` في الطلبات القديمة |
| `shippingCapApplied` | boolean | اتفرض سقف المنطقة بدل المجموع |
| `shippingUncappedCost` | رقم \| null | الشحن لولا السقف |
| `assemblyTotal` | رقم | مجموع (رسم القطعة × الكمية). **داخل `grandTotal` فعلًا**. صفر لو مفيش تركيب |
| `preferredDeliveryDate` | `YYYY-MM-DD` \| null | اللي العميل اختاره عند الطلب |
| `preferredDeliverySlot` | `MORNING` \| `AFTERNOON` \| `EVENING` \| null | |
| `scheduledDeliveryAt` | تاريخ+وقت بـ offset \| null | الموعد اللي الأدمن اتفق عليه مع العميل |

حالة الطلب (`fulfillmentStatus`) فيها قيمة جديدة: **`AWAITING_SCHEDULE`** ("بانتظار تحديد الموعد") بين `CONFIRMED` و`PROCESSING`. اختيارية: الأدمن يقدر يروح من `CONFIRMED` لـ`PROCESSING` مباشرة.

### بند الطلب (`OrderItemResponse`)

| الحقل | ملاحظة |
|---|---|
| `assemblyFee` | رسم القطعة وقت الشراء (snapshot). صفر لو مفيش |
| `assemblyTotal` | `assemblyFee × quantity` |

### الفاتورة

**الـ JSON (`InvoiceResponse`) ما اتغيّرش.** اللي اتغيّر الـ PDF: صف **التركيب** وصف **رسوم الدفع عند الاستلام** بعد صف الشحن، بيظهروا بس لو أكبر من صفر. إيميل تأكيد الطلب كمان.

### المنطقة (`/admin/shipping/zones`)

| الحقل | ملاحظة |
|---|---|
| `rates[]` | `{sizeClass, unitCost}` — سعر **وحدة واحدة** من كل مقاس |
| `maxShippingCost` | سقف الشحن للطلب الواحد. `null` = بلا سقف |

### المحافظة

- `GET /geo/governorates` (عام): `shippingRates[]`, `maxShippingCost`, `served` (انظر ٣).
- `GET /admin/shipping/governorates` (جديد): `id, code, nameAr, nameEn, zoneId (null لو مقفولة), zoneCode, served`.

### السلة وعرض الشحن

- `CartResponse.assemblyTotal`.
- `ShippingQuoteResponse`: `assemblyTotal`, `shippingCapApplied`, `uncappedCost`, `breakdown[]`.

---

## ٢. الـ endpoints الجديدة

### عامة (بدون token)

| المسار | الغرض |
|---|---|
| `GET /api/v1/portfolio?categoryId=&page=&size=` | المعرض المنشور، مرقّم (`PageResponse`، الافتراضي 12، الحد الأقصى 50). الترتيب ثابت: `displayOrder` ثم الأحدث. الـ `sort` بيتتجاهل |
| `GET /api/v1/portfolio/{slug}` | عنصر واحد بكل صوره. **بالـ slug مش بالـ id** |
| `POST /api/v1/custom-requests` | طلب تصنيع/مقاس مخصوص. بيرجّع `requestNumber` و`attachmentToken` (**مرة واحدة** — احفظه) |
| `POST /api/v1/custom-requests/{id}/attachments` | رفع صورة (multipart، part اسمه `file`، header `X-Request-Token`) |

body الطلب: `type` (`SIZE_VARIANT`\|`MADE_TO_ORDER`\|`CUSTOM_WORK`)، `contactName`، `phone`، `governorateId` إجباريين؛ `productId` (إجباري لـ`SIZE_VARIANT`)، `widthCm/heightCm/depthCm` (واحد على الأقل لـ`SIZE_VARIANT`)، `quantity`, `notes`, `email`, `altPhone`, `area`, `streetAddress` اختيارية.
الصور: JPEG/PNG/WebP/AVIF، حتى 5 ميجا، **5 صور كحد أقصى للطلب**، والطلب لازم يكون `NEW`. الـ token صالح 24 ساعة.

### أدمن (`ROLE_ADMIN`، غير كده 403)

| المسار | الغرض |
|---|---|
| `PATCH /admin/orders/{id}/schedule` | `{scheduledDeliveryAt, note?}` — تحديد/تغيير موعد التوصيل |
| `GET /admin/shipping/governorates` | كل المحافظات، المقفولة كمان |
| `PUT /admin/shipping/governorates/{id}/zone` | `{zoneId}` — فتح محافظة أو نقلها لمنطقة |
| `DELETE /admin/shipping/governorates/{id}/zone` | قفل محافظة |
| `PUT /admin/shipping/zones/{zoneId}/max-shipping-cost` | `{maxShippingCost}` (`null` يشيل السقف) |
| `GET /admin/custom-requests` | قائمة مرقّمة. فلاتر: `status,type,governorateId,q,from,to` |
| `GET /admin/custom-requests/{id}` | الطلب بكل صوره + `governorateServed` |
| `PATCH /admin/custom-requests/{id}/status` | `{status, note}` — `CONTACTED`/`ACCEPTED`/`REJECTED` (الملاحظة إجبارية للرفض) |
| `PATCH /admin/custom-requests/{id}/quote` | `{amount, note?}` — السعر الإجمالي شامل الضريبة لكل الكمية |
| `GET/POST /admin/portfolio`, `GET/PUT /admin/portfolio/{id}` | قائمة (`includeArchived=false` افتراضيًا)، إنشاء (يبدأ مسودة)، تعديل |
| `PATCH /admin/portfolio/{id}/publish` | `{published:true\|false}` — حالة صريحة مش toggle |
| `DELETE /admin/portfolio/{id}` | **أرشفة** مش حذف |
| `PATCH /admin/portfolio/{id}/restore` | رجوع من الأرشيف **كمسودة** |
| `GET/POST /admin/portfolio/{id}/images` | قائمة/رفع (multipart، `file`، 20 صورة كحد أقصى، أول صورة رئيسية) |
| `PATCH` / `DELETE /admin/portfolio/{id}/images/{imageId}` | تعديل alt/ترتيب/رئيسية، حذف صورة |

حالات المعرض: **مسودة** (`published=false`) ← **منشور** ← **مؤرشف** (`archivedAt` مضبوط، و`published` بيتحط `false` غصب). المسودة والمؤرشف مش بيظهروا في العام.

`PUT /admin/portfolio/{id}` استبدال كامل: الحقول الاختيارية (`titleEn`, الوصفين, `categoryId`, `completedAt`) **بتتمسح لو ما اتبعتتش**؛ `slug` و`displayOrder` بيفضلوا. ابعت النموذج كله.

---

## ٣. ⚠️ اللي اتغيّر في سلوك قائم

### ١) السلة: بقت بترفض أي منتج مش `READY_MADE`
- `POST /cart/items` على منتج `MADE_TO_ORDER` أو `CUSTOM_WORK` → **409 `PRODUCT_NOT_PURCHASABLE`**. قبل كده كان بيتقبل لو المنتج منشور.
- نفس الشرط بيتطبق على الـ merge (سلة الضيف بتدخل حساب العميل): الأصناف دي بتتخطّى بصمت.
- سطر موجود في سلة قديمة لمنتج اتغيّر نوعه بيطلع **warning** بكود `PRODUCT_UNAVAILABLE` في `warnings[]` ويخلّي `checkoutReady=false`.
- **الواجهة:** منتج مش `READY_MADE` ما يظهرش عليه زرار "أضف للسلة". بدله زرار "اطلب تصنيع" (طلب مخصوص، قسم ٢).

### ٢) الشحن: بقى بحسب المقاس والكمية مش بسعر ثابت للمنطقة
المنطقة كان ليها سعر واحد (+ وزن + حد شحن مجاني). دلوقتي: `سعر الوحدة (منطقة، مقاس) × الكمية` لكل مقاس، ثم المجموع **مسقوف** بـ `maxShippingCost` للمنطقة.

**حقول اتشالت أو بقت ميتة:**

| الـ endpoint | اللي اتشال | البديل |
|---|---|---|
| `POST /shipping/quote` | `baseCost` | `uncappedCost` + `breakdown[]` + `shippingCapApplied` |
| `POST /shipping/quote` | `freeShippingThreshold`, `amountToFreeShipping` | **لسه موجودين لكن `null` دايمًا.** مفيش "اشتري بكذا واحصل على شحن مجاني" تاني. `freeShippingApplied` معناها دلوقتي إن `shippingCost = 0` (القاهرة الكبرى سعرها صفر) |
| `POST /shipping/quote` | الوزن | `totalWeightGrams` لسه بيرجع للعلم بس، **ما بيأثرش في السعر** |
| `GET /geo/governorates` | `shippingCost` | `shippingRates[]` (`{sizeClass, unitCost}`) + `maxShippingCost`. لو المحافظة مقفولة: `shippingRates=[]` و`served=false` |
| `GET /admin/shipping/zones` | `baseCost`, `freeShippingOver` | `rates[]` + `maxShippingCost` |
| `PUT /admin/shipping/rates` | `maxWeightGrams`, `costPerExtraKg`, `freeShippingOver` | بيتتجاهلوا لو اتبعتوا (مش بيرفض). **`sizeClass` بقى إجباري** → من غيره 400. وبقى السعر لـ(منطقة، مقاس) واحد في كل نداء |

- `codFee` وأيام التوصيل خاصة بالمنطقة كلها: بتتكتب على كل مقاسات المنطقة.
- **كارت المنتج:** ما تحسبش الشحن من `shippingRates` على الجانب العميل. اعرض رقم `POST /shipping/quote` (هو نفسه اللي بيتحسب بيه الطلب).

### ٣) محافظة مقفولة: `GOVERNORATE_NOT_SERVED` بدل `SHIPPING_RATE_NOT_CONFIGURED`
- جنوب سيناء وأسوان والأقصر **مقفولة** (مش في أي منطقة). فضلت في `GET /geo/governorates` بـ `served:false` — **ما اتشالتش من القائمة**.
- بقى بيرجع **409 `GOVERNORATE_NOT_SERVED`** في: `POST /shipping/quote`، `POST /orders`، وإنشاء/تعديل عنوان في `/me/addresses`. قبل كده `POST /orders` كان بيرجع `SHIPPING_RATE_NOT_CONFIGURED` في الحالة دي. الكود ده **لسه موجود** لكن بقى معناه حاجة تانية (أدمن بيحاول يحط محافظة في منطقة ناقصها سعر مقاس).
- **الواجهة:** اظهر المحافظة المقفولة معطّلة/برسالة "لا نوصّل لها حاليًا"، ولا تخليها تختارها في نموذج العنوان. (طلب التصنيع بيقبل محافظة مقفولة — ده مقصود.)

### ٤) `estimatedTotal` بقى شامل التركيب
- السلة: `estimatedTotal = subtotal − discount + assemblyTotal`.
- عرض الشحن: `estimatedTotal = subtotal + assemblyTotal + shippingCost + codFee`.
- `subtotal` يفضل **البضاعة بس**. الخصم بيتطبق على البضاعة فقط، مش على التركيب.
- `taxIncluded` في السلة بقى شامل ضريبة التركيب. الأرقام كلها صفر دلوقتي لأن الرسوم لسه `0`.
- **الواجهة:** اعرض صف "التركيب" في الملخص لما `assemblyTotal > 0` بس.

### ٥) الشحن بقى بيحتاج `shippingSizeClass` على كل منتج في السلة
- منتج في السلة من غير مقاس → **409 `SHIPPING_SIZE_MISSING`** (الـ `detail` بيذكر الـ SKU) في `POST /shipping/quote` وفي `POST /orders`. بيحصل **قبل** حجز المخزون، فمفيش أثر جانبي.
- كل المنتجات القديمة مقاسها `null` (انظر ١). لحد ما الأدمن يحدد مقاس لكل منتج، الشراء ممكن يفشل.
- `POST /admin/products` و`PUT /admin/products/{id}` على `READY_MADE` من غير مقاس → **400 `VALIDATION_FAILED`**. يعني **نموذج تعديل منتج قديم هيرفض الحفظ** لحد ما الأدمن يختار المقاس.
- **الواجهة:** `shippingSizeClass` select إجباري في نموذج المنتج لما النوع `READY_MADE`.

### ٦) نشر المنتج: الـ variant بقى مطلوب لـ`READY_MADE` بس
`MADE_TO_ORDER` و`CUSTOM_WORK` تقدر تتنشر من غير variants (مفيش حاجة تتباع). `READY_MADE` لسه محتاج variant واحد على الأقل.

### ٧) إنشاء طلب (`POST /orders`): حقلين اختياريين جدد وقواعدهم
- `preferredDeliveryDate`: مش في الماضي (بتوقيت القاهرة)، ولا بعد أكتر من **90 يوم** → 400 `VALIDATION_FAILED`.
- `preferredDeliverySlot` من غير تاريخ → 400.
- الطلبات اللي مش بتبعتهم شغّالة زي ما كانت.

### ٨) الطلب الجديد في حالة `AWAITING_SCHEDULE`
- `AWAITING_SCHEDULE → PROCESSING` من غير موعد (`scheduledDeliveryAt`) → **409 `DELIVERY_NOT_SCHEDULED`** (كود خاص، مش `INVALID_STATUS_TRANSITION`، والرسالة بتقول يعمل إيه).
- `PATCH /admin/orders/{id}/schedule`: مسموح من `CONFIRMED` لحد `DELIVERY_FAILED` (ينفع بعد محاولة فاشلة). مرفوض للـ `PENDING` وللمنتهي → **409 `ORDER_NOT_SCHEDULABLE`**. الموعد لازم في المستقبل (400). ما بيغيّرش حالة الطلب.
- العميل يقدر **يلغي** الطلب وهو `AWAITING_SCHEDULE` لحد ما يتشحن.
- حالة الطلب الجديدة لازم تتضاف في فلاتر/تلوين/timeline لوحة الطلبات. لوحة التحكم (`/admin/dashboard`) بقت بتعد `AWAITING_SCHEDULE` ضمن الطوابير، والطلب اللي موعده لسه في المستقبل **مش بيعتبر متأخر**.

### ٩) الفاتورة/الإكسل
- التصدير المحاسبي (`/admin/exports/orders/accounting`): اتضاف عمودين بعد "الشحن" مباشرة: **"التركيب"** ثم **"رسوم الدفع عند الاستلام"**، قبل "الإجمالي". أي حاجة بتقرا الإكسل بالـ index هتتزحلق (19 عمود بدل 17).
- `codFee` كان داخل `grandTotal` ومش ظاهر في الفاتورة ولا التصدير — اتصلّح، والفواتير القديمة اتعمل لها backfill من طلبها.

### ١٠) عناوين الـ IP ومعدّلات الحد
وراء proxy (nginx/ngrok) الـ IP بقى بيتاخد من `X-Forwarded-For`، فكل زائر ليه حدّه (قبل كده الموقع كله كان بيتشارك حد واحد). ده بيأثر على login وOTP كمان.

---

## ٤. أكواد الأخطاء الجديدة

| الكود | الحالة | متى | الواجهة تعمل إيه |
|---|---|---|---|
| `PRODUCT_NOT_PURCHASABLE` | 409 | إضافة منتج مش `READY_MADE` للسلة | وجّه لطلب التصنيع |
| `SHIPPING_SIZE_MISSING` | 409 | منتج في السلة بدون مقاس شحن (quote / order) | رسالة "تعذّر حساب الشحن، تواصل معنا"؛ الأدمن يحدد المقاس |
| `GOVERNORATE_NOT_SERVED` | 409 | محافظة مقفولة (quote / order / عنوان) | عطّل الاختيار، رسالة "لا نوصّل لها حاليًا" |
| `DELIVERY_NOT_SCHEDULED` | 409 | `AWAITING_SCHEDULE → PROCESSING` بدون موعد | افتح نموذج تحديد الموعد |
| `ORDER_NOT_SCHEDULABLE` | 409 | تحديد موعد لطلب `PENDING` أو منتهي | عطّل الزرار حسب الحالة |
| `CUSTOM_REQUEST_NOT_FOUND` | 404 | طلب غير موجود، **أو** token ناقص/غلط/منتهي/لطلب تاني (مقصود إنها واحدة) | "انتهت صلاحية رفع الصور" |
| `CUSTOM_REQUEST_CLOSED` | 409 | رفع صورة بعد ما الطلب خرج من `NEW` | اخفي رفع الصور |
| `CUSTOM_REQUEST_QUOTE_REQUIRED` | 409 | `ACCEPTED` قبل تسعير | افتح نموذج التسعير الأول |
| `PORTFOLIO_ITEM_NOT_FOUND` | 404 | slug/id مش موجود، أو مسودة، أو مؤرشف (العام مش بيفرّق) | صفحة 404 |
| `PORTFOLIO_ITEM_ARCHIVED` | 409 | نشر أو تعديل صور عنصر مؤرشف | اعرض زرار "استرجاع" |
| `SLUG_ALREADY_EXISTS` | 409 | slug صريح مستخدم (منتج، وبقى للمعرض كمان) | "الـ slug مستخدم" |
| `RATE_LIMITED` | 429 | تجاوز حد المعدل (قسم ٥) | "حاول بعد شوية" |

أكواد موجودة ومهمة في الحالات الجديدة: `VALIDATION_FAILED` (400) لأي مدخل غلط (ملف مش صورة، ملف فاضي أو أكبر من 5 ميجا، تاريخ مرفوض، `REJECTED` بدون ملاحظة)، `INVALID_STATUS_TRANSITION` (409)، `INVALID_PHONE_FORMAT` (400)، `FILE_REQUIRED` (400)، `CATEGORY_NOT_FOUND` / `PRODUCT_NOT_FOUND` (404).
شكل الخطأ: `{ "code": "...", ... }` — اعتمد على `code` مش على الرسالة.

---

## ٥. حدود المعدل

كلها **لكل IP**، والعدّاد في ذاكرة السيرفر (بيتصفّر لما يتعمل restart). التجاوز → **429 `RATE_LIMITED`**.

| المسار | الحد |
|---|---|
| `POST /auth/login` | 10 / 15 دقيقة لكل IP، **و** 5 / 15 دقيقة لكل حساب |
| إرسال OTP (`/auth/otp/**`) | 10 / ساعة لكل IP، وكمان 5 / ساعة لكل رقم أو إيميل |
| `POST /custom-requests` | **10 / ساعة** لكل IP |
| `POST /custom-requests/{id}/attachments` | **30 / ساعة** لكل IP، **المحاولات الفاشلة بتتعد** |

مفيش header `Retry-After` — اعرض رسالة عامة.

---

## ٦. القرارات المتخذة وسببها

**الكتالوج والشحن**
- `fulfillmentType` = طريقة البيع مش النوع، فالتصنيف يفضل بالـ category.
- مقاس الشحن `null` على المنتجات القديمة، ومش بنخمّنه: مقاس مخمّن مش بيتفرق عن الحقيقي بعدين. الطلب بيتوقف بـ `SHIPPING_SIZE_MISSING` بدل ما يتسعّر غلط.
- السقف بيطبق على المجموع بعد الحساب لكل مقاس، و`breakdown` دايمًا بيعرض الأسطر **قبل** السقف عشان الشاشة تشرح الرقم.
- القاهرة الكبرى مجانية عن طريق أسعارها = 0، مش عن طريق "حد شحن مجاني".
- أسعار المنطقة `REMOTE` **تقديرية** (مش من عرض شركة شحن) ومعلّمة للمراجعة لما نتعاقد.
- قفلنا **جنوب سيناء كلها** مش شرم بس: السيستم مفيش فيه مستوى مدينة، وشرم هي المدينة الوحيدة اللي بتتميّز. الفتح بيتم من الأدمن (`PUT .../zone`) مش SQL.
- المحافظة المقفولة بتفضل ظاهرة (`served:false`) وما بتتخبّاش، عشان الأدمن يقدر يفتحها والعميل يفهم.

**التركيب (Assembly)**
- رسم **للقطعة**، فرسم × الكمية لكل بند.
- شامل الضريبة؛ الضريبة بتتستخرج لكل بند بنسبته وتتجمع (زي باقي الأسعار)، **مش** بتتضاف فوق.
- خارج خصم السلة (الخصم بيتوزع على البضاعة بس).
- متسجّل على البند (snapshot) فتغيير المنتج بعد كده ما بيمسّش الطلبات القديمة.
- **الرسوم كلها صفر حاليًا.** الحقول جاهزة لكن مفيش حاجة بتتحسب لحد ما تتسعّر منتجات.
- حساب ضريبة التركيب بنسبة البضاعة **محتاج تأكيد محاسب** (معلّم في الكود مع تعليق الشحن).

**الطلب والتوصيل**
- حالة `AWAITING_SCHEDULE` اختيارية (للأثاث: التاريخ بيتحدد قبل ما البضاعة تغادر المخزن). الشحن لسه هو اللي بيخصم المخزون، والتسليم هو اللي بيصدر الفاتورة.
- `preferredDeliveryDate` طلب من العميل مش وعد؛ الموعد الفعلي هو `scheduledDeliveryAt` اللي الأدمن بيتفق عليه.
- `DELIVERY_NOT_SCHEDULED` كود لوحده: الحركة مسموحة وناقص حاجة، فالرسالة لازم تقول إيه هي.
- اللوحة بتفرّق بين طلب متأخر فعلًا وطلب موعده لسه في المستقبل.

**طلبات التصنيع (Custom requests)**
- الطلب **مش بيع**: مفيش مخزون محجوز ولا سعر لحد ما الأدمن يسعّر والعميل يوافق.
- الترقيم `REQ-2026-000001` متسلسل **بدون فجوات** في السنة (العميل بيتقاله الرقم تليفونيًا)، ولذلك الطلبات ما بتتحذفش: المرفوض `REJECTED` مش ممسوح.
- الـ `attachmentToken` متوقّع (24 ساعة): العميل بيملا الفورم على موبايل ويرجع بعد ما يصوّر. الحماية إنه موقّع ومربوط بطلب واحد، مش إنه قصير.
- الصور بتتفحص بالبايتات الحقيقية مش بالـ Content-Type ولا اسم الملف (ملف HTML متسمّي `.png` بيترفض)، والامتداد المحفوظ من النوع المكتشف.
- كل تغيير حالة وكل تسعير في الـ audit log.

**المعرض (Portfolio)**
- كيان مستقل مش منتجات `CUSTOM_WORK`: منتج بلا سعر ولا مخزون جوه جدول المنتجات بيبوّظ التقارير ومعدل نفاد المخزون.
- الـ slug **لاتيني** مولّد من العنوان العربي (نفس آلية المنتجات) بلاحقة `-2` `-3` لو مكرر، ويقبل override من الأدمن؛ override مكرر بيرجع 409 بدل ما يتغيّر في صمت. بيتشارك على واتساب وفيسبوك فلازم يكون URL نضيف.
- الـ slug فريد حتى بين المؤرشف: لينك اتشارك قبل كده ما يبدأش يعرض حاجة تانية.
- تغيير slug لعنصر موجود بيكسر اللينكات اللي اتشاركت (مفيش redirect).
- الأرشفة مش حذف والصور بتفضل، والاسترجاع بيرجّع **مسودة** (النشر خطوة متعمّدة)، فغلطة أرشفة بتتصلح من الـ API مش من SQL.
- النشر/الإخفاء/الأرشفة/الاسترجاع كلها في الـ audit (`PORTFOLIO_STATUS_CHANGED` بالحالة `DRAFT`/`LIVE`/`ARCHIVED`)، زي المحافظات.

---

## ٧. اللي لسه ناقص أو مؤجّل

- **المرتجع الجزئي لا يرد رسم التركيب.** حساب الرد بيرجّع قيمة البضاعة بس. القرار مع المحاسب قبل ما الرسوم تبقى أكبر من صفر.
- **تحويل طلب التصنيع لأوردر:** حالة `CONVERTED` **مرفوضة** حاليًا (409 `INVALID_STATUS_TRANSITION`)، و`convertedOrderId` موجود وبيرجع `null` دايمًا. محتاج feature لوحده.
- **الخصومات/الكوبونات:** `couponCode` و`discountTotal` موجودين وبيرجعوا صفر؛ موديول العروض لسه ما اتبنيش.
- **ضريبة الشحن ورسوم الدفع عند الاستلام:** معتبرة غير خاضعة للضريبة لحد تأكيد المحاسب.
- **المعرض:** مفيش صورة مصغّرة (thumb)، ومفيش redirect لما الـ slug يتغيّر.
- **بيانات المتجر القانونية بالعربي** (اسم الشركة/العنوان على الفاتورة) لازم تتدخل من `PUT /admin/settings/store-profile` على قاعدة `royal`.
- **عدّاد معدّل الطلبات** في الذاكرة: بيتصفّر مع restart، ومش مشترك بين أكتر من instance.
- **Audit log للتركيب والمعرض:** المعرض اتغطّى؛ باقي قرارات المحتوى (مثلًا تغيير `assemblyFee` على المنتج) **لسه مش بتتسجل**.

---

## ٨. حاجات معروفة محتاجة إصلاح

لا تأثير على الواجهة اليوم، لكن لازم تتصلّح.

- **`RemittanceService.nextReference()` فيه سباق قراءة-ثم-كتابة.** بياخد أعلى رقم موجود ويزوّد عليه واحد (`REM-2026-0001`…). تسويتين بيتسجلوا في نفس اللحظة ممكن يقرأوا نفس الرقم. التعليق في الكود بيقول "الفجوة مش مهمة" وده صح للفجوة، لكن **الفشل** ممكن.
  - اللي بيحصل فعليًا (من قراءة الكود، **ما اتجرّبش بنداءين متوازيين**): `reference` عليه unique index (`uq_remittance_reference`)، فمفيش رقمين متطابقين بيتخزّنوا. التسوية التانية بتفشل بخطأ تكامل بيانات (`DataIntegrityViolationException`) وبتتلغي كلها، والأدمن يعيد المحاولة.
  - الإصلاح المناسب: عدّاد صف بقفل لكل سنة، زي `invoice_sequence` و`custom_order_request_sequence`. لسه ما اتعملش.
- **إصلاحين اتعملوا هنا ولسه ناقصين في repo VELORA الأصلي:**
  - ثغرة امتداد الملف المرفوع (الامتداد كان بييجي من اسم الملف أو الـ Content-Type؛ هنا بيجي من البايتات الحقيقية).
  - سباق عدّاد الفواتير.
  - اتفقنا إنك هتطبّقهم بنفسك على VELORA الأصلي.
- **`ix_prod_search` وحد الـ 1700 بايت.** بناء قاعدة من الـ baseline بيطبع تحذير، وده متوقع ومش خطأ. الـ index ممكن نظريًا يعدّي الحد، والكود بيمنع ده (`search_text` محدود بـ 800 حرف، والحد الفعلي 849). التفاصيل في `src/main/resources/db/README.md` تحت "Known issues". ما تصلّحش التحذير بتعديل الـ baseline.
