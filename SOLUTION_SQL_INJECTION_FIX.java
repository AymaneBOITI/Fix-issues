// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║        SOLUTION SQL INJECTION FIX (FORTIFY)                                ║
// ║        Approche : PreparedStatement paramétré + buildInClause()            ║
// ║        Aucune nouvelle classe. Aucune constante SQL modifiée.              ║
// ╚══════════════════════════════════════════════════════════════════════════════╝
//
//  PRINCIPE :
//  ─────────
//  buildInClause(int n) génère "IN (?, ?, ?)" à partir d'un INT.
//  Un int n'est PAS une donnée taintée → Fortify ne flag pas.
//  Les vraies valeurs passent par setString() via PreparedStatement.
//
//  String.format(SQL_CONSTANT, buildInClause(branches.size()))
//    → injecte de la STRUCTURE ("IN (?,?,?)"), pas de la DATA
//    → 100% safe, 0 constante modifiée, 0 classe créée
//
//  FICHIERS MODIFIÉS : 7
//  ─────────────────
//  1. SqlHandler.java           → +2 méthodes (buildInClause, toParams)
//  2. Main.java                 → String → List<String>, suppr buildBranchCondition
//  3. PaymentSqlHandler.java    → 5 méthodes corrigées
//  4. IPaymentSqlHandler.java   → 5 signatures mises à jour
//  5. PaymentProcessor.java     → String → List<String>
//  6. ResponseProcessor.java    → String → List<String>
//  7. PaymentSqlQueries.java    → AUCUNE MODIFICATION


// ════════════════════════════════════════════════════════════════════════════════
//  ÉTAPE 1 : SqlHandler.java — Ajouter 2 méthodes protected (après ligne ~128)
// ════════════════════════════════════════════════════════════════════════════════

// Ajouter les imports en haut :
import java.util.Collections;
import java.util.List;

// Ajouter après prepareStatement() :

    /**
     * Génère une clause IN paramétrée : "IN (?, ?, ?)"
     */
    protected String buildInClause(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("La clause IN nécessite au moins 1 paramètre");
        }
        return "IN (" + String.join(", ", Collections.nCopies(count, "?")) + ")";
    }

    /**
     * Convertit une List<String> en Object[] pour PreparedStatement.
     */
    protected Object[] toParams(List<String> values) {
        return values.stream().map(String::trim).toArray(Object[]::new);
    }


// ════════════════════════════════════════════════════════════════════════════════
//  ÉTAPE 2 : Main.java
// ════════════════════════════════════════════════════════════════════════════════

// ── 2a. Champ (ligne 43) ──

    // AVANT :
    private String branchesCondition = "";
    // APRÈS :
    private List<String> branches = new ArrayList<>();

// ── 2b. SUPPRIMER buildBranchCondition() (ligne ~153) — EN ENTIER ──

    // SUPPRIMER :
    private static String buildBranchCondition(List<String> branches) {
        return " in ('" + branches.stream()
            .map(String::trim)
            .collect(joining("', '")) + "')";
    }

// ── 2c. initProcessors() ──

    // AVANT :
    List<String> branches = this.sqlHandler.getBranches(cdeName);
    this.branchesCondition = buildBranchCondition(branches);
    this.responseProcessor = new ResponseProcessor(branchesCondition, sqlHandler, mqService, auditHandler);
    this.paymentProcessor = new PaymentProcessor(parameter, branchesCondition, sqlHandler, mqService, auditHandler, paymentController);

    // APRÈS :
    this.branches = this.sqlHandler.getBranches(cdeName);
    this.responseProcessor = new ResponseProcessor(branches, sqlHandler, mqService, auditHandler);
    this.paymentProcessor = new PaymentProcessor(parameter, branches, sqlHandler, mqService, auditHandler, paymentController);

// ── 2d. process() ──

    // AVANT :
    this.sqlHandler.purgeWorkingTables(branchesCondition);
    this.sqlHandler.loadWorkingTables(branchesCondition);
    // APRÈS :
    this.sqlHandler.purgeWorkingTables(branches);
    this.sqlHandler.loadWorkingTables(branches);


// ════════════════════════════════════════════════════════════════════════════════
//  ÉTAPE 3 : PaymentSqlQueries.java — AUCUNE MODIFICATION
// ════════════════════════════════════════════════════════════════════════════════
//
//  Les constantes gardent leur %s. On ne touche à rien.
//  String.format(CONSTANT, buildInClause(n)) injecte "IN (?,?)" à la place du %s.
//  C'est de la structure SQL, pas de la data → safe.


// ════════════════════════════════════════════════════════════════════════════════
//  ÉTAPE 4 : PaymentSqlHandler.java — 5 méthodes à corriger
// ════════════════════════════════════════════════════════════════════════════════

// ── 4a. getNewPayments() (ligne ~168) ──

    // AVANT :
    @Override
    public List<Payment> getNewPayments(String branchCondition) throws SQLException {
        List<Payment> payments = new ArrayList<>();
        try (PreparedStatement ps = this.prepareStatement(
                String.format(SELECT_NEW_PAYMENTS, branchCondition), null);
             ResultSet rs = ps.executeQuery()) {
            // ...
        }
    }

    // APRÈS :
    @Override
    public List<Payment> getNewPayments(List<String> branches) throws SQLException {
        List<Payment> payments = new ArrayList<>();
        String query = String.format(SELECT_NEW_PAYMENTS, buildInClause(branches.size()));
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
            LOG.error("Could not get new payments", e);
            throw e;
        }
        return payments;
    }


// ── 4b. loadWorkingTables() (ligne ~603) ──

    // AVANT :
    @Override
    public void loadWorkingTables(String branchCondition) throws SQLException {
        updateQuery(String.format(INSERT_OUT_DIFF, branchCondition), null);
        updateQuery(String.format(INSERT_ROLE_DIFF, branchCondition), null);
        updateQuery(String.format(INSERT_OUT_DATA, branchCondition), null);
        updateQuery(String.format(INSERT_ROLE_DATA, branchCondition), null);
    }

    // APRÈS :
    @Override
    public void loadWorkingTables(List<String> branches) throws SQLException {
        String inClause = buildInClause(branches.size());
        Object[] params = toParams(branches);
        updateQuery(String.format(INSERT_OUT_DIFF, inClause), params);
        updateQuery(String.format(INSERT_ROLE_DIFF, inClause), params);
        updateQuery(String.format(INSERT_OUT_DATA, inClause), params);
        updateQuery(String.format(INSERT_ROLE_DATA, inClause), params);
    }


// ── 4c. purgeWorkingTables() ──

    // AVANT :
    @Override
    public void purgeWorkingTables(String branchCondition) throws SQLException {
        updateQuery(String.format(PURGE_OUT_DIFF, branchCondition), null);
        updateQuery(String.format(PURGE_ROLE_DIFF, branchCondition), null);
    }

    // APRÈS :
    @Override
    public void purgeWorkingTables(List<String> branches) throws SQLException {
        String inClause = buildInClause(branches.size());
        Object[] params = toParams(branches);
        updateQuery(String.format(PURGE_OUT_DIFF, inClause), params);
        updateQuery(String.format(PURGE_ROLE_DIFF, inClause), params);
    }


// ── 4d. updatePendingSwiftMsgtoFailInDB() (ligne ~803) ──

    // AVANT :
    @Override
    public Integer updatePendingSwiftMsgtoFailInDB(String branchCondition) throws PaymentException {
        int nack = 0;
        List<Payment> payments = new ArrayList<>();
        try (PreparedStatement ps = this.prepareStatement(
                String.format(SELECT_PENDING_SWIFT_MSG, branchCondition),
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

    // APRÈS :
    @Override
    public Integer updatePendingSwiftMsgtoFailInDB(List<String> branches) throws PaymentException {
        int nack = 0;
        List<Payment> payments = new ArrayList<>();

        String query = String.format(SELECT_PENDING_SWIFT_MSG, buildInClause(branches.size()));

        // Fusionner params : STATUS_DELV + branches
        // ⚠️ Vérifier l'ordre des ? dans SELECT_PENDING_SWIFT_MSG :
        //   si "WHERE status = ? AND branch %s"  → {STATUS_DELV, br1, br2}
        //   si "WHERE branch %s AND status = ?"  → {br1, br2, STATUS_DELV}
        Object[] branchParams = toParams(branches);
        Object[] allParams = new Object[1 + branchParams.length];
        allParams[0] = STATUS_DELV;
        System.arraycopy(branchParams, 0, allParams, 1, branchParams.length);

        try (PreparedStatement ps = this.prepareStatement(query, allParams);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Payment payment = new Payment();
                payment.setMsgKey(rs.getString("MSG_KEY"));
                payments.add(payment);
            }
        } catch (SQLException e) {
            LOG.error("Could not get pending payments", e);
            throw new PaymentException("Could not get pending payments", e);
        }
        for (Payment payment : payments) {
            this.updateStatus(payment.getMsgKey(), STATUS_FAIL, "TIME_OUT");
            nack++;
        }
        return nack;
    }


// ── 4e. getMsgKeyForItl() (ligne ~743) ──
//   ⚠️ Cette méthode utilise la CONCATÉNATION directe (pas String.format)
//   SELECT_KEY_USING_MSG_DAY_AND_SEQ + branchCondition
//   On remplace par : SELECT_KEY_USING_MSG_DAY_AND_SEQ + buildInClause(n)

    // AVANT :
    @Override
    public String getMsgKeyForItl(String branchCondition, String day, String seq) throws PaymentException {
        String msgKey = null;
        Object[] params = {day, seq, STATUS_DELV, STATUS_FAIL};
        try (PreparedStatement ps = this.prepareStatement(
                SELECT_KEY_USING_MSG_DAY_AND_SEQ + branchCondition, params);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                msgKey = rs.getString("MSG_KEY");
            }
        } catch (SQLException e) {
            throw new PaymentException(
                    "SQLError: Could not get MSG_KEY of ITL message with query "
                    + SELECT_KEY_USING_MSG_DAY_AND_SEQ + branchCondition
                    + " params " + Arrays.toString(params), e);
        }
        return msgKey;
    }

    // APRÈS :
    @Override
    public String getMsgKeyForItl(List<String> branches, String day, String seq) throws PaymentException {
        String msgKey = null;

        String query = SELECT_KEY_USING_MSG_DAY_AND_SEQ + buildInClause(branches.size());

        // Params existants {day, seq, STATUS_DELV, STATUS_FAIL} viennent AVANT
        // car leurs ? sont dans SELECT_KEY_USING_MSG_DAY_AND_SEQ.
        // Les branches viennent APRÈS car IN(?,?) est concaténé à la fin.
        Object[] branchParams = toParams(branches);
        Object[] allParams = new Object[4 + branchParams.length];
        allParams[0] = day;
        allParams[1] = seq;
        allParams[2] = STATUS_DELV;
        allParams[3] = STATUS_FAIL;
        System.arraycopy(branchParams, 0, allParams, 4, branchParams.length);

        try (PreparedStatement ps = this.prepareStatement(query, allParams);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                msgKey = rs.getString("MSG_KEY");
            }
        } catch (SQLException e) {
            throw new PaymentException(
                    "SQLError: Could not get MSG_KEY of ITL message", e);
        }
        return msgKey;
    }


// ════════════════════════════════════════════════════════════════════════════════
//  ÉTAPE 5 : IPaymentSqlHandler.java — Mettre à jour les signatures
// ════════════════════════════════════════════════════════════════════════════════

    // AVANT :
    List<Payment> getNewPayments(String branchCondition) throws SQLException;
    void loadWorkingTables(String branchCondition) throws SQLException;
    void purgeWorkingTables(String branchCondition) throws SQLException;
    Integer updatePendingSwiftMsgtoFailInDB(String branchCondition) throws PaymentException;
    String getMsgKeyForItl(String branchCondition, String day, String seq) throws PaymentException;

    // APRÈS :
    List<Payment> getNewPayments(List<String> branches) throws SQLException;
    void loadWorkingTables(List<String> branches) throws SQLException;
    void purgeWorkingTables(List<String> branches) throws SQLException;
    Integer updatePendingSwiftMsgtoFailInDB(List<String> branches) throws PaymentException;
    String getMsgKeyForItl(List<String> branches, String day, String seq) throws PaymentException;


// ════════════════════════════════════════════════════════════════════════════════
//  ÉTAPE 6 : PaymentProcessor.java — String → List<String>
// ════════════════════════════════════════════════════════════════════════════════

    // Champ (ligne 41) :
    // AVANT :  private final String branchesCondition;
    // APRÈS :  private final List<String> branches;

    // Constructeur 1 (ligne ~47) :
    // AVANT :  ..., String branchesCondition, ...
    // APRÈS :  ..., List<String> branches, ...
    //          this.branches = branches;

    // Constructeur 2 (ligne ~60) :
    // AVANT :  ..., String branchesCondition, ...
    // APRÈS :  ..., List<String> branches, ...
    //          this.branches = branches;

    // buildNewPayments() (ligne ~75) :
    // AVANT :  List<Payment> newPayments = this.sqlHandler.getNewPayments(this.branchesCondition);
    // APRÈS :  List<Payment> newPayments = this.sqlHandler.getNewPayments(this.branches);


// ════════════════════════════════════════════════════════════════════════════════
//  ÉTAPE 7 : ResponseProcessor.java — String → List<String>
// ════════════════════════════════════════════════════════════════════════════════

    // Champ (ligne 12) :
    // AVANT :  private final String branchCondition;
    // APRÈS :  private final List<String> branches;

    // Constructeur :
    // AVANT :  ..., String branchCondition, ...
    // APRÈS :  ..., List<String> branches, ...
    //          this.branches = branches;

    // processAckNackFromItl() (ligne ~95) :
    // AVANT :  nbNack = this.sqlHandler.updatePendingSwiftMsgtoFailInDB(this.branchCondition);
    // APRÈS :  nbNack = this.sqlHandler.updatePendingSwiftMsgtoFailInDB(this.branches);

    // processItlAck() (ligne ~117) :
    // AVANT :  String msgKey = this.sqlHandler.getMsgKeyForItl(this.branchCondition, day, seq);
    // APRÈS :  String msgKey = this.sqlHandler.getMsgKeyForItl(this.branches, day, seq);


// ════════════════════════════════════════════════════════════════════════════════
//  RÉSUMÉ
// ════════════════════════════════════════════════════════════════════════════════
/*
  ╔═══════════════════════════════════╦════════════════════════════════════════════╗
  ║ FICHIER                          ║ MODIFICATION                              ║
  ╠═══════════════════════════════════╬════════════════════════════════════════════╣
  ║ SqlHandler.java                  ║ +buildInClause() +toParams()              ║
  ╠═══════════════════════════════════╬════════════════════════════════════════════╣
  ║ Main.java                        ║ String→List<String>, suppr buildBranch..()║
  ╠═══════════════════════════════════╬════════════════════════════════════════════╣
  ║ PaymentSqlQueries.java           ║ AUCUNE MODIFICATION                       ║
  ╠═══════════════════════════════════╬════════════════════════════════════════════╣
  ║ PaymentSqlHandler.java           ║ 5 méthodes : String→List<String>          ║
  ║                                  ║ String.format(CONST, buildInClause(n))    ║
  ╠═══════════════════════════════════╬════════════════════════════════════════════╣
  ║ IPaymentSqlHandler.java          ║ 5 signatures mises à jour                 ║
  ╠═══════════════════════════════════╬════════════════════════════════════════════╣
  ║ PaymentProcessor.java            ║ String→List<String> (champ+constructeurs) ║
  ╠═══════════════════════════════════╬════════════════════════════════════════════╣
  ║ ResponseProcessor.java           ║ String→List<String> (champ+constructeur)  ║
  ╚═══════════════════════════════════╩════════════════════════════════════════════╝

  0 nouvelle classe. 0 constante SQL modifiée. 7 fichiers touchés.
*/


// ════════════════════════════════════════════════════════════════════════════════
//  POURQUOI C'EST SAFE
// ════════════════════════════════════════════════════════════════════════════════
/*
  AVANT (vulnérable) :
    getBranches() → buildBranchCondition() → " in ('BR1','BR2')"
    String.format(query, branchCondition) → DATA dans le SQL → ❌ INJECTION

  APRÈS :
    getBranches() → List<String> branches
    branches.size() → int 3         ← un int ne porte PAS de payload SQL
    buildInClause(3) → "IN (?,?,?)" ← STRUCTURE, pas de data
    String.format(query, "IN (?,?,?)") → que des ? dans le SQL → ✅ SAFE
    toParams(branches) → setString(1,"BR1"), setString(2,"BR2") → ✅ PARAMÉTRÉ

  La chaîne de taint est cassée à .size() : int ≠ tainted string.
  Fortify ne flag pas.
*/
