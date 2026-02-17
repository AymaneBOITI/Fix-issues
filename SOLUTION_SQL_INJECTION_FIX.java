// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║        SOLUTION COMPLÈTE - SQL INJECTION FIX (FORTIFY)                     ║
// ║        *** SANS NOUVELLE CLASSE ***                                        ║
// ║                                                                            ║
// ║  On ajoute 2 méthodes protected dans SqlHandler.java (parent existant)     ║
// ║  et on modifie les appelants pour utiliser des paramètres bindés.          ║
// ╚══════════════════════════════════════════════════════════════════════════════╝


// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║  ÉTAPE 1 : SqlHandler.java — Ajouter 2 méthodes protected                 ║
// ║  (après prepareStatement(), vers ligne ~128)                               ║
// ╚══════════════════════════════════════════════════════════════════════════════╝

// AJOUTER cet import en haut :
import java.util.Collections;
import java.util.List;

// AJOUTER ces 2 méthodes après prepareStatement() (ligne ~128) :

    /**
     * Génère une clause IN paramétrée : "IN (?, ?, ?)"
     * pour remplacer les String.format(%s) vulnérables.
     */
    protected String buildInClause(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("La clause IN doit avoir au moins un paramètre");
        }
        return "IN (" + String.join(", ", Collections.nCopies(count, "?")) + ")";
    }

    /**
     * Convertit une List<String> en Object[] pour les paramètres PreparedStatement.
     */
    protected Object[] toParams(List<String> values) {
        return values.stream().map(String::trim).toArray(Object[]::new);
    }


// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║  ÉTAPE 2 : Main.java — Remplacer String par List<String>                   ║
// ╚══════════════════════════════════════════════════════════════════════════════╝

// ──── 2a. Champ (ligne 43) ────

    // AVANT :
    private String branchesCondition = "";

    // APRÈS :
    private List<String> branches = new ArrayList<>();


// ──── 2b. SUPPRIMER buildBranchCondition() (vers ligne ~153) ────

    // SUPPRIMER EN ENTIER :
    private static String buildBranchCondition(List<String> branches) {
        return " in ('" + branches.stream()
            .map(String::trim)
            .collect(joining("', '")) + "')";
    }


// ──── 2c. Modifier initProcessors() ────

    // AVANT :
    private void initProcessors(String cdeName) throws VarEnvException, SQLException, ArgumentException {
        Main.setInstance(this);
        this.sqlHandler.setAutoCommit(false);
        this.auditHandler = new AuditHandler(cdeName, this.sqlHandler);
        this.auditHandler.loadLogId();
        List<String> branches = this.sqlHandler.getBranches(cdeName);
        this.branchesCondition = buildBranchCondition(branches);
        this.responseProcessor = new ResponseProcessor(branchesCondition, sqlHandler, mqService, auditHandler);
        IPaymentController paymentController = new PaymentController(sqlHandler, auditHandler, cdeName);
        this.paymentProcessor = new PaymentProcessor(parameter, branchesCondition, sqlHandler, mqService, auditHandler, paymentController);
    }

    // APRÈS :
    private void initProcessors(String cdeName) throws VarEnvException, SQLException, ArgumentException {
        Main.setInstance(this);
        this.sqlHandler.setAutoCommit(false);
        this.auditHandler = new AuditHandler(cdeName, this.sqlHandler);
        this.auditHandler.loadLogId();
        this.branches = this.sqlHandler.getBranches(cdeName);  // ← stocker directement la liste
        this.responseProcessor = new ResponseProcessor(branches, sqlHandler, mqService, auditHandler);
        IPaymentController paymentController = new PaymentController(sqlHandler, auditHandler, cdeName);
        this.paymentProcessor = new PaymentProcessor(parameter, branches, sqlHandler, mqService, auditHandler, paymentController);
    }


// ──── 2d. Modifier process() ────

    // AVANT :
    this.sqlHandler.purgeWorkingTables(branchesCondition);
    this.sqlHandler.loadWorkingTables(branchesCondition);

    // APRÈS :
    this.sqlHandler.purgeWorkingTables(branches);
    this.sqlHandler.loadWorkingTables(branches);


// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║  ÉTAPE 3 : PaymentSqlQueries.java — SPLIT des constantes contenant %s     ║
// ║                                                                            ║
// ║  STRATÉGIE ZÉRO RISQUE : on coupe chaque constante avec %s en 2 morceaux  ║
// ║  _P1 (avant le %s) et _P2 (après le %s). Aucun String.format() utilisé.   ║
// ║  → Fortify ne peut JAMAIS flagger cette approche.                         ║
// ╚══════════════════════════════════════════════════════════════════════════════╝

// ╔═══════════════════════════════════════════════════════════════════════════╗
// ║  POUR CHAQUE CONSTANTE : couper exactement à l'endroit du %s            ║
// ║                                                                         ║
// ║  AVANT :  "...WHERE TRIM(X.COL) %s AND NOT EXISTS..."                   ║
// ║                                  ↑↑                                     ║
// ║  APRÈS :  _P1 = "...WHERE TRIM(X.COL) "                                ║
// ║           _P2 = " AND NOT EXISTS..."                                    ║
// ║                                                                         ║
// ║  Si le %s est en toute FIN de la requête → _P2 = "" (chaîne vide)      ║
// ╚═══════════════════════════════════════════════════════════════════════════╝

// Exemple concret — SELECT_NEW_PAYMENTS (le %s est à la FIN) :
//
//  AVANT :
//    public static final String SELECT_NEW_PAYMENTS =
//        "SELECT ... WHERE TRIM(M.IMT_CDE_BRANCH) %s";
//
//  APRÈS :
//    public static final String SELECT_NEW_PAYMENTS_P1 =
//        "SELECT ... WHERE TRIM(M.IMT_CDE_BRANCH) ";
//    public static final String SELECT_NEW_PAYMENTS_P2 = "";  // rien après

// Exemple concret — INSERT_OUT_DIFF (le %s est au MILIEU) :
//
//  AVANT :
//    public static final String INSERT_OUT_DIFF =
//        "INSERT INTO ... WHERE TRIM(O.IMT_CDE_BRANCH) %s AND NOT EXISTS ...";
//
//  APRÈS :
//    public static final String INSERT_OUT_DIFF_P1 =
//        "INSERT INTO ... WHERE TRIM(O.IMT_CDE_BRANCH) ";
//    public static final String INSERT_OUT_DIFF_P2 =
//        " AND NOT EXISTS ...";

// ──── LISTE COMPLÈTE DES CONSTANTES À SPLITTER ────
//
//  1. SELECT_NEW_PAYMENTS       → SELECT_NEW_PAYMENTS_P1 + _P2
//  2. INSERT_OUT_DIFF           → INSERT_OUT_DIFF_P1     + _P2
//  3. INSERT_ROLE_DIFF          → INSERT_ROLE_DIFF_P1    + _P2
//  4. INSERT_OUT_DATA           → INSERT_OUT_DATA_P1     + _P2
//  5. INSERT_ROLE_DATA          → INSERT_ROLE_DATA_P1    + _P2
//  6. PURGE_OUT_DIFF            → PURGE_OUT_DIFF_P1      + _P2
//  7. PURGE_ROLE_DIFF           → PURGE_ROLE_DIFF_P1     + _P2
//  8. SELECT_PENDING_SWIFT_MSG  → SELECT_PENDING_SWIFT_MSG_P1 + _P2
//
//  ⚠️ SELECT_KEY_USING_MSG_DAY_AND_SEQ : PAS DE SPLIT (pas de %s, concat directe)
//     On remplace juste : CONSTANTE + branchCondition → CONSTANTE + buildInClause(n)
//
//  MODE D'EMPLOI : ouvrir la constante, trouver le %s, couper avant/après.
//  Si _P2 est vide, mettre "" (il sera optimisé par le compilateur).


// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║  ÉTAPE 4 : PaymentSqlHandler.java — Corriger les méthodes                  ║
// ║  PATTERN UNIVERSEL (aucun String.format, aucun risque Fortify) :           ║
// ║                                                                            ║
// ║    String query = CONSTANTE_P1 + buildInClause(n) + CONSTANTE_P2;         ║
// ║    Object[] params = toParams(branches);                                   ║
// ║    prepareStatement(query, params);  // ou updateQuery(query, params)      ║
// ╚══════════════════════════════════════════════════════════════════════════════╝

// ==================== 4a. getNewPayments() (ligne ~168) ====================

    // AVANT :
    @Override
    public List<Payment> getNewPayments(String branchCondition) throws SQLException {
        List<Payment> payments = new ArrayList<>();
        try (PreparedStatement ps = this.prepareStatement(
                String.format(SELECT_NEW_PAYMENTS, branchCondition), null);  // ❌ INJECTION
             ResultSet rs = ps.executeQuery()) {
            // ...
        }
    }

    // APRÈS :
    @Override
    public List<Payment> getNewPayments(List<String> branches) throws SQLException {
        List<Payment> payments = new ArrayList<>();
        String query = SELECT_NEW_PAYMENTS_P1 + buildInClause(branches.size()) + SELECT_NEW_PAYMENTS_P2;
        //  → "select ... where TRIM(M.IMT_CDE_BRANCH) IN (?, ?, ?)"  (aucun String.format !)
        try (PreparedStatement ps = this.prepareStatement(query, toParams(branches));
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Payment payment = new Payment();
                payment.setMsgType(SwiftType.getSwiftType(rs.getString("IMT_CDE_MSG_TYPE")));
                payment.setMsgBranch(rs.getString("IMT_CDE_BRANCH"));
                payment.setMsgId(rs.getString("IMT_RID_IMT_OUT"));
                payment.setMsgAmount(rs.getString("IMT_AMT_OUT_TOT_FMT"));
                payment.setMsgCurrency(rs.getString("IMT_CDE_CURRENCY"));
                payment.setValueDate(rs.getString("IMT_DTE_VALUE_DATE_FMT"));
                payment.setEffectiveDate(rs.getString("IMT_DTE_BUSINESS_FMT"));
                payment.setSenderToReceiverInfo(rs.getString("IMT_TXT_SDR_RVR_TX"));
                payment.setPidDeal(rs.getString("PID_DEAL"));
                payment.setPidFacility(rs.getString("IMT_PID_FACILITY"));
                payment.setRidCashflow(rs.getString("IMT_RID_CASHFLOW"));
                payment.setRidOwner(rs.getString("IMT_RID_OWNER"));
                payment.setOwnerType(rs.getString("IMT_CDE_OWNER_TYPE"));
                payment.setCdeBankOp(rs.getString("IMT_CDE_BNK_OP"));
                payment.setDtlCharges(rs.getString("IMT_CDE_DTLS_CHGES"));
                payment.setDealName(rs.getString("DEAL_NME"));
                payment.setFacilityName(rs.getString("FAC_NME"));
                payments.add(payment);
            }
        } catch (SQLException e) {
            LOG.error("Could not get new payments with query " + SELECT_NEW_PAYMENTS_P1, e);
            throw e;
        }
        return payments;
    }


// ==================== 4b. loadWorkingTables() (ligne ~603 - 2ème point Fortify) ====================

    // AVANT :
    @Override
    public void loadWorkingTables(String branchCondition) throws SQLException {
        updateQuery(String.format(INSERT_OUT_DIFF, branchCondition), null);       // ❌
        updateQuery(String.format(INSERT_ROLE_DIFF, branchCondition), null);      // ❌
        updateQuery(String.format(INSERT_OUT_DATA, branchCondition), null);       // ❌
        updateQuery(String.format(INSERT_ROLE_DATA, branchCondition), null);      // ❌
    }

    // APRÈS :
    @Override
    public void loadWorkingTables(List<String> branches) throws SQLException {
        String inClause = buildInClause(branches.size());  // "IN (?, ?, ?)"
        Object[] params = toParams(branches);

        updateQuery(INSERT_OUT_DIFF_P1  + inClause + INSERT_OUT_DIFF_P2,  params);  // ✅ zéro String.format
        updateQuery(INSERT_ROLE_DIFF_P1 + inClause + INSERT_ROLE_DIFF_P2, params);  // ✅
        updateQuery(INSERT_OUT_DATA_P1  + inClause + INSERT_OUT_DATA_P2,  params);  // ✅
        updateQuery(INSERT_ROLE_DATA_P1 + inClause + INSERT_ROLE_DATA_P2, params);  // ✅
    }


// ==================== 4c. purgeWorkingTables() ====================

    // AVANT :
    @Override
    public void purgeWorkingTables(String branchCondition) throws SQLException {
        updateQuery(String.format(PURGE_OUT_DIFF, branchCondition), null);     // ❌
        updateQuery(String.format(PURGE_ROLE_DIFF, branchCondition), null);    // ❌
    }

    // APRÈS :
    @Override
    public void purgeWorkingTables(List<String> branches) throws SQLException {
        String inClause = buildInClause(branches.size());
        Object[] params = toParams(branches);

        updateQuery(PURGE_OUT_DIFF_P1  + inClause + PURGE_OUT_DIFF_P2,  params);  // ✅ zéro String.format
        updateQuery(PURGE_ROLE_DIFF_P1 + inClause + PURGE_ROLE_DIFF_P2, params);  // ✅
    }

// Ne pas oublier l'import en haut de PaymentSqlHandler.java :
// import java.util.Collections;  (si pas déjà présent)


// ==================== 4d. Toute autre méthode avec String.format + branchCondition ====================
// ╔═══════════════════════════════════════════════════════════════════════╗
// ║  PATTERN UNIVERSEL (ne plus jamais se poser la question) :           ║
// ║                                                                      ║
// ║    String inClause = buildInClause(branches.size());                 ║
// ║    Object[] params = toParams(branches);                             ║
// ║    String query = CONSTANTE_P1 + inClause + CONSTANTE_P2;            ║
// ║    prepareStatement(query, params);  // ou updateQuery(query, params)║
// ║                                                                      ║
// ║  → ZÉRO String.format() dans le code → Fortify n'a RIEN à dire     ║
// ║  → Les constantes SQL sont splitées en _P1 / _P2                     ║
// ║  → On concatène "IN (?, ?, ?)" entre les 2 morceaux                  ║
// ║  → Les vraies valeurs passent par setString() → SÉCURISÉ           ║
// ╚═══════════════════════════════════════════════════════════════════════╝


// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║  ÉTAPE 5 : IPaymentSqlHandler.java — Mettre à jour l'interface             ║
// ╚══════════════════════════════════════════════════════════════════════════════╝

    // AVANT :
    List<Payment> getNewPayments(String branchCondition) throws SQLException;
    void loadWorkingTables(String branchCondition) throws SQLException;
    void purgeWorkingTables(String branchCondition) throws SQLException;

    // APRÈS :
    List<Payment> getNewPayments(List<String> branches) throws SQLException;
    void loadWorkingTables(List<String> branches) throws SQLException;
    void purgeWorkingTables(List<String> branches) throws SQLException;


// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║  ÉTAPE 6 : ResponseProcessor + PaymentProcessor                            ║
// ║  Modifier les constructeurs : String → List<String>                        ║
// ╚══════════════════════════════════════════════════════════════════════════════╝

// ==================== PaymentProcessor.java ====================
// Champ ligne 41 : String branchesCondition → List<String> branches
// 2 constructeurs + buildNewPayments() à modifier

    // ── Champ ──
    // AVANT :
    private final String branchesCondition;  // 3 usages
    // APRÈS :
    private final List<String> branches;     // (ajouter import java.util.List)


    // ── Constructeur 1 (ligne ~47) ──
    // AVANT :
    public PaymentProcessor(InterfaceParameter param, String branchesCondition,
                            IPaymentSqlHandler sqlHandler, IMessageMqService mqService,
                            IAuditHandler auditHandler, IPaymentController paymentController)
            throws VarEnvException {
        this.param = param;
        this.sqlHandler = sqlHandler;
        this.mqService = mqService;
        this.branchesCondition = branchesCondition;
        // ...
    }

    // APRÈS :
    public PaymentProcessor(InterfaceParameter param, List<String> branches,
                            IPaymentSqlHandler sqlHandler, IMessageMqService mqService,
                            IAuditHandler auditHandler, IPaymentController paymentController)
            throws VarEnvException {
        this.param = param;
        this.sqlHandler = sqlHandler;
        this.mqService = mqService;
        this.branches = branches;
        // ...
    }


    // ── Constructeur 2 (ligne ~60) ──
    // AVANT :
    public PaymentProcessor(String branchesCondition, IPaymentSqlHandler sqlHandler,
                            IMessageMqService mqService, IAuditHandler auditHandler,
                            IPaymentController paymentController)
            throws VarEnvException {
        this.sqlHandler = sqlHandler;
        this.mqService = mqService;
        this.branchesCondition = branchesCondition;
        // ...
    }

    // APRÈS :
    public PaymentProcessor(List<String> branches, IPaymentSqlHandler sqlHandler,
                            IMessageMqService mqService, IAuditHandler auditHandler,
                            IPaymentController paymentController)
            throws VarEnvException {
        this.sqlHandler = sqlHandler;
        this.mqService = mqService;
        this.branches = branches;
        // ...
    }


    // ── buildNewPayments() (ligne ~73) — CRITIQUE ──
    // AVANT :
    List<Payment> newPayments = this.sqlHandler.getNewPayments(this.branchesCondition);
    // APRÈS :
    List<Payment> newPayments = this.sqlHandler.getNewPayments(this.branches);


// ==================== ResponseProcessor.java ====================
// Le branchCondition est stocké mais N'EST PAS utilisé dans des requêtes SQL
// directement. Il faut quand même changer le type pour la cohérence
// et couper la propagation du "tainted data" vue par Fortify.

    // ── Champ (ligne 12) ──
    // AVANT :
    private final String branchCondition;  // 3 usages
    // APRÈS :
    private final List<String> branches;


    // ── Constructeur (ligne ~37) ──
    // AVANT :
    public ResponseProcessor(String branchCondition, IPaymentSqlHandler sqlHandler,
                            IMessageMqService mqService,
                            IAuditHandler logHandler) {
        this.branchCondition = branchCondition;
        this.sqlHandler = sqlHandler;
        this.mqService = mqService;
        this.auditHandler = logHandler;
    }

    // APRÈS :
    public ResponseProcessor(List<String> branches, IPaymentSqlHandler sqlHandler,
                            IMessageMqService mqService,
                            IAuditHandler logHandler) {
        this.branches = branches;
        this.sqlHandler = sqlHandler;
        this.mqService = mqService;
        this.auditHandler = logHandler;
    }

    // Si branchCondition est utilisé quelque part dans ResponseProcessor
    // dans du SQL, appliquer le même pattern buildInClause() + toParams().
    // ⚠️ OUI, branchCondition EST utilisé dans 2 appels SQL :

    // ── processAckNackFromItl() ligne ~95 ──
    // AVANT :
    nbNack = this.sqlHandler.updatePendingSwiftMsgtoFailInDB(this.branchCondition);
    // APRÈS :
    nbNack = this.sqlHandler.updatePendingSwiftMsgtoFailInDB(this.branches);

    // ── processItlAck() ligne ~117 ──
    // AVANT :
    String msgKey = this.sqlHandler.getMsgKeyForItl(this.branchCondition, day, seq);
    // APRÈS :
    String msgKey = this.sqlHandler.getMsgKeyForItl(this.branches, day, seq);


// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║  ÉTAPE 7 : PaymentSqlHandler.java — 2 méthodes supplémentaires            ║
// ║  updatePendingSwiftMsgtoFailInDB() et getMsgKeyForItl()                    ║
// ╚══════════════════════════════════════════════════════════════════════════════╝

// Il faut aussi splitter SELECT_PENDING_SWIFT_MSG dans PaymentSqlQueries.java :
//   SELECT_PENDING_SWIFT_MSG_P1 (avant %s) + SELECT_PENDING_SWIFT_MSG_P2 (après %s)
//
// Par contre SELECT_KEY_USING_MSG_DAY_AND_SEQ n'a PAS de %s (concaténation directe),
// donc PAS de split nécessaire — on remplace juste la concat par buildInClause().

// ==================== updatePendingSwiftMsgtoFailInDB() (ligne ~803) ====================

    // AVANT (VULNÉRABLE) :
    @Override
    public Integer updatePendingSwiftMsgtoFailInDB(String branchCondition) throws PaymentException {
        int nack = 0;
        List<Payment> payments = new ArrayList<>();
        try (PreparedStatement ps = this.prepareStatement(
                String.format(SELECT_PENDING_SWIFT_MSG, branchCondition),  // ❌ INJECTION
                new Object[]{STATUS_DELV});
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Payment payment = new Payment();
                payment.setMsgKey(rs.getString("MSG_KEY"));
                payments.add(payment);
            }
        } catch (SQLException e) {
            LOG.error("Could not get pending payments with query " + SELECT_PENDING_SWIFT_MSG, e);
            throw new PaymentException("Could not get pending payments with query " + SELECT_PENDING_SWIFT_MSG, e);
        }
        for (Payment payment : payments) {
            this.updateStatus(payment.getMsgKey(), STATUS_FAIL, "TIME_OUT");
            nack++;
        }
        return nack;
    }

    // APRÈS (SÉCURISÉ — zéro String.format) :
    @Override
    public Integer updatePendingSwiftMsgtoFailInDB(List<String> branches) throws PaymentException {
        int nack = 0;
        List<Payment> payments = new ArrayList<>();

        // Construire la query sans String.format :
        String inClause = buildInClause(branches.size());  // "IN (?, ?, ?)"
        String query = SELECT_PENDING_SWIFT_MSG_P1 + inClause + SELECT_PENDING_SWIFT_MSG_P2;

        // Fusionner les params : STATUS_DELV (existant) + branches
        // L'ordre dépend de la position des ? dans la requête :
        //   "... WHERE status = ?₁ AND TRIM(branch) IN (?₂, ?₃, ?₄) ..."
        //              STATUS_DELV↑                    ↑branches
        Object[] branchParams = toParams(branches);
        Object[] allParams = new Object[1 + branchParams.length];
        allParams[0] = STATUS_DELV;  // le ? existant vient en premier
        System.arraycopy(branchParams, 0, allParams, 1, branchParams.length);

        try (PreparedStatement ps = this.prepareStatement(query, allParams);  // ✅ SAFE
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Payment payment = new Payment();
                payment.setMsgKey(rs.getString("MSG_KEY"));
                payments.add(payment);
            }
        } catch (SQLException e) {
            LOG.error("Could not get pending payments with query " + SELECT_PENDING_SWIFT_MSG_P1, e);
            throw new PaymentException("Could not get pending payments with query " + SELECT_PENDING_SWIFT_MSG_P1, e);
        }
        for (Payment payment : payments) {
            this.updateStatus(payment.getMsgKey(), STATUS_FAIL, "TIME_OUT");
            nack++;
        }
        return nack;
    }

    // ⚠️ IMPORTANT : vérifier l'ordre des ? dans SELECT_PENDING_SWIFT_MSG.
    // Si le ? (STATUS_DELV) est APRÈS le %s dans la requête, inverser :
    //   allParams = branches d'abord, puis STATUS_DELV à la fin.


// ==================== getMsgKeyForItl() (ligne ~743) ====================

    // AVANT (VULNÉRABLE — concaténation directe, pas String.format) :
    @Override
    public String getMsgKeyForItl(String branchCondition, String day, String seq) throws PaymentException {
        String msgKey = null;
        Object[] params = {day, seq, STATUS_DELV, STATUS_FAIL};
        try (PreparedStatement ps = this.prepareStatement(
                SELECT_KEY_USING_MSG_DAY_AND_SEQ + branchCondition,  // ❌ INJECTION
                params);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                msgKey = rs.getString("MSG_KEY");
            }
        } catch (SQLException e) {
            throw new PaymentException(
                    "SQLError: Could not get MSG_KEY of ITL message with query " + SELECT_KEY_USING_MSG_DAY_AND_SEQ
                    + branchCondition + " params " + Arrays.toString(params), e);
        }
        return msgKey;
    }

    // APRÈS (SÉCURISÉ — pas de split nécessaire car pas de %s) :
    @Override
    public String getMsgKeyForItl(List<String> branches, String day, String seq) throws PaymentException {
        String msgKey = null;

        // branchCondition était concaténé à la FIN → on remplace par buildInClause()
        String query = SELECT_KEY_USING_MSG_DAY_AND_SEQ + buildInClause(branches.size());
        //  → "...AND TRIM(branch) IN (?, ?, ?)"

        // Les params existants {day, seq, STATUS_DELV, STATUS_FAIL} viennent AVANT
        // car leurs ? sont dans la partie SELECT_KEY_USING_MSG_DAY_AND_SEQ.
        // Les branches viennent APRÈS car le IN(?,?) est à la fin.
        Object[] branchParams = toParams(branches);
        Object[] allParams = new Object[4 + branchParams.length];
        allParams[0] = day;
        allParams[1] = seq;
        allParams[2] = STATUS_DELV;
        allParams[3] = STATUS_FAIL;
        System.arraycopy(branchParams, 0, allParams, 4, branchParams.length);

        try (PreparedStatement ps = this.prepareStatement(query, allParams);  // ✅ SAFE
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                msgKey = rs.getString("MSG_KEY");
            }
        } catch (SQLException e) {
            throw new PaymentException(
                    "SQLError: Could not get MSG_KEY of ITL message with query " + SELECT_KEY_USING_MSG_DAY_AND_SEQ
                    + " params " + Arrays.toString(allParams), e);
        }
        return msgKey;
    }

// ⚠️ NOTE : SELECT_KEY_USING_MSG_DAY_AND_SEQ n'a PAS besoin d'être splitté
// car il n'a pas de %s — la branchCondition était simplement concaténée à la fin.


// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║  ÉTAPE 8 : IPaymentSqlHandler.java — Signatures supplémentaires           ║
// ╚══════════════════════════════════════════════════════════════════════════════╝

    // AJOUTER/MODIFIER dans l'interface :
    // AVANT :
    Integer updatePendingSwiftMsgtoFailInDB(String branchCondition) throws PaymentException;
    String getMsgKeyForItl(String branchCondition, String day, String seq) throws PaymentException;

    // APRÈS :
    Integer updatePendingSwiftMsgtoFailInDB(List<String> branches) throws PaymentException;
    String getMsgKeyForItl(List<String> branches, String day, String seq) throws PaymentException;


// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║  RÉSUMÉ DES MODIFICATIONS                                                  ║
// ╚══════════════════════════════════════════════════════════════════════════════╝
/*
  ╔═════════════════════════════════════╦═══════════════════════════════════════════╗
  ║ FICHIER                            ║ MODIFICATION                             ║
  ╠═════════════════════════════════════╬═══════════════════════════════════════════╣
  ║ SqlHandler.java                    ║ + buildInClause() et toParams()          ║
  ║                                    ║ + imports Collections, List              ║
  ╠═════════════════════════════════════╬═══════════════════════════════════════════╣
  ║ Main.java                          ║ String branchesCondition → List<String>  ║
  ║                                    ║ SUPPRIMER buildBranchCondition()         ║
  ║                                    ║ Modifier initProcessors() et process()   ║
  ╠═════════════════════════════════════╬═══════════════════════════════════════════╣
  ║ PaymentSqlQueries.java             ║ SPLIT des constantes avec %s :      ║
  ║                                    ║ _P1 (avant %s) + _P2 (après %s)    ║
  ║                                    ║ (8 constantes à splitter)           ║
  ╠═════════════════════════════════════╬═══════════════════════════════════════════╣
  ║ PaymentSqlHandler.java             ║ getNewPayments(List<String>)             ║
  ║                                    ║ loadWorkingTables(List<String>)          ║
  ║                                    ║ purgeWorkingTables(List<String>)         ║
  ║                                    ║ updatePendingSwiftMsgtoFailInDB(List)    ║
  ║                                    ║ getMsgKeyForItl(List, day, seq)          ║
  ║                                    ║ CONCAT _P1 + buildInClause() + _P2      ║
  ╠═════════════════════════════════════╬═══════════════════════════════════════════╣
  ║ IPaymentSqlHandler.java            ║ 5 signatures: String → List<String>     ║
  ╠═════════════════════════════════════╬═══════════════════════════════════════════╣
  ║ PaymentProcessor.java              ║ Champ: String → List<String>            ║
  ║                                    ║ 2 constructeurs: String → List<String>  ║
  ║                                    ║ buildNewPayments(): passer branches     ║
  ╠═════════════════════════════════════╬═══════════════════════════════════════════╣
  ║ IPaymentProcessor.java             ║ (si interface existe) adapter            ║
  ╠═════════════════════════════════════╬═══════════════════════════════════════════╣
  ║ ResponseProcessor.java             ║ Champ: String → List<String>            ║
  ║                                    ║ Constructeur: String → List<String>     ║
  ║                                    ║ processAckNackFromItl(): passer branches ║
  ║                                    ║ processItlAck(): passer branches        ║
  ╠═════════════════════════════════════╬═══════════════════════════════════════════╣
  ║ IResponseProcessor.java            ║ (si interface existe) adapter            ║
  ╚═════════════════════════════════════╩═══════════════════════════════════════════╝

  AUCUNE NOUVELLE CLASSE CRÉÉE. Tout est dans l'existant.
*/


// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║  POURQUOI CETTE SOLUTION RÉSOUT FORTIFY                                    ║
// ╚══════════════════════════════════════════════════════════════════════════════╝
/*
  AVANT (VULNÉRABLE):
  ┌────────────────────────────────────────────────────────────────────────┐
  │  getBranches() → ["BR1","BR2"]                                        │
  │  buildBranchCondition() → " in ('BR1', 'BR2')"      ← SQL BRUT      │
  │  String.format(query, branchCondition)               ← CONCATÉNATION │
  │  prepareStatement("...WHERE branch in ('BR1','BR2')")← VULNÉRABLE    │
  └────────────────────────────────────────────────────────────────────────┘

  APRÈS (SÉCURISÉ - ZÉRO String.format):
  ┌────────────────────────────────────────────────────────────────────────┐
  │  getBranches() → ["BR1","BR2"]                                        │
  │  QUERY_P1 + buildInClause(2) + QUERY_P2                              │
  │    → "...WHERE branch IN (?, ?) AND NOT EXISTS..."  ← SAFE           │
  │  toParams(branches) → Object[]{"BR1","BR2"}                           │
  │  prepareStatement(query, params)                                      │
  │  → ps.setString(1, "BR1");  ps.setString(2, "BR2")  ← PARAMÉTRÉ     │
  └────────────────────────────────────────────────────────────────────────┘

  ✅ Aucune donnée n'est jamais injectée dans le SQL
  ✅ ZÉRO appel à String.format() avec des données taintées
  ✅ Tout passe par PreparedStatement avec setString()
  ✅ Fortify ne détectera plus de SQL injection (impossible à flagger)
  ✅ Pas de nouvelle classe
  ✅ Impact minimal sur le code existant
*/
