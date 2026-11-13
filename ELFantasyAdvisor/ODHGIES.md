# Οδηγίες: upload στο GitHub → APK

Όλα γίνονται από τον browser, χωρίς git στον υπολογιστή.

## 1. Νέο repo
1. GitHub → **New repository**
2. Όνομα: `el-fantasy-advisor` · **Public** · **χωρίς** README / .gitignore / license
3. **Create repository**

## 2. Ανέβασμα αρχείων
1. Αποσυμπίεσε το zip.
2. Στη σελίδα του άδειου repo πάτα **«uploading an existing file»**.
3. Σύρε μέσα **τα περιεχόμενα** του φακέλου `ELFantasyAdvisor`, όχι τον ίδιο τον φάκελο:
   `app/`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `README.md`, `ODHGIES.md`
4. **Commit changes**

> Οι κρυφοί φάκελοι/αρχεία (`.github`, `.gitignore`) συνήθως **δεν** ανεβαίνουν με drag & drop.
> Γι' αυτό τα φτιάχνουμε με το χέρι στο βήμα 3.

## 3. Workflow για το build
1. **Add file → Create new file**
2. Όνομα αρχείου: `.github/workflows/build.yml` (οι κάθετες φτιάχνουν μόνες τους τους φακέλους)
3. Επικόλλησε το περιεχόμενο του `.github/workflows/build.yml` από το zip
4. **Commit changes**. Αυτό ξεκινάει αυτόματα το πρώτο build.

(Προαιρετικά, με τον ίδιο τρόπο, φτιάξε και το `.gitignore`.)

## 4. Κατέβασμα του APK
1. Καρτέλα **Actions → Build APK →** το τελευταίο run (θέλει ~5–8 λεπτά)
2. Κάτω κάτω, στα **Artifacts → ELFantasyAdvisor-apk** (zip με το `app-debug.apk`)
3. Πέρασέ το στο κινητό και εγκατέστησέ το (επέτρεψε «εγκατάσταση από άγνωστες πηγές»)

Για νέο build χωρίς αλλαγές: **Actions → Build APK → Run workflow**.

## 5. Πρώτη χρήση
1. Επέτρεψε τις ειδοποιήσεις.
2. Πάτα **Ανανέωση**. Η πρώτη φορά θέλει 1–3 λεπτά (κατεβάζει ιστορικό ~360 παικτών).
3. Αν βγάλει σφάλμα **401**, πήγαινε στις **Ρυθμίσεις** και βάλε token (οδηγίες μέσα στην οθόνη).
4. Από εκεί και πέρα ενημερώνεται μόνη της κάθε ~2 ώρες και σε ειδοποιεί όταν βγουν νέες προτάσεις.

## Αν αποτύχει το build
Άνοιξε το run που απέτυχε, κλικ στο βήμα με το κόκκινο ✗ και στείλε μου τις τελευταίες ~40 γραμμές του log.
