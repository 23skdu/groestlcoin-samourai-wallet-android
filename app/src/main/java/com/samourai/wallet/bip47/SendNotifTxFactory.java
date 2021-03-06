package com.samourai.wallet.bip47;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Pair;
import android.widget.Toast;
//import android.util.Log;

import org.apache.commons.lang3.StringUtils;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.DumpedPrivateKey;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.ScriptException;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Transaction.SigHash;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.crypto.MnemonicException;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import com.samourai.wallet.OpCallback;
import com.samourai.wallet.R;
import com.samourai.wallet.SamouraiWallet;
import com.samourai.wallet.access.AccessFactory;
import com.samourai.wallet.api.APIFactory;
import com.samourai.wallet.bip47.rpc.PaymentAddress;
import com.samourai.wallet.hd.HD_Address;
import com.samourai.wallet.hd.HD_WalletFactory;
import com.samourai.wallet.send.BitcoinAddress;
import com.samourai.wallet.send.BitcoinScript;
import com.samourai.wallet.send.MyTransactionInput;
import com.samourai.wallet.send.MyTransactionOutPoint;
import com.samourai.wallet.send.UnspentOutputsBundle;
import com.samourai.wallet.util.AddressFactory;
import com.samourai.wallet.util.CharSequenceX;
import com.samourai.wallet.util.Hash;
import com.samourai.wallet.util.PushTx;
import com.samourai.wallet.util.WebUtil;
import com.samourai.wallet.bip47.rpc.PaymentCode;
import com.samourai.wallet.bip47.rpc.SecretPoint;

import org.bitcoinj.script.ScriptOpCodes;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.spongycastle.util.encoders.Hex;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SendNotifTxFactory	{

    public static final BigInteger _bNotifTxValue = SamouraiWallet.bDust;
    public static final BigInteger _bSWFee = SamouraiWallet.bFee;
    public static final BigInteger _bFee = SamouraiWallet.bFee;

    public static final BigInteger _bNotifTxTotalAmount = _bFee.add(_bSWFee).add(_bNotifTxValue);

    private static final String SAMOURAI_NOTIF_TX_FEE_ADDRESS = "3K8eqP6j14JzPnmRMG6exTsNo8iUZHEH5e";  //GRS


    private static SendNotifTxFactory instance = null;
    private static Context context = null;

    private SendNotifTxFactory () { ; }

    private String[] from = null;
    private HashMap<String,String> froms = null;

    private boolean sentChange = false;
    private int changeAddressesUsed = 0;

    List<MyTransactionOutPoint> allOutputs = null;

    public static SendNotifTxFactory getInstance(Context ctx) {

        context = ctx;

        if(instance == null)	{
            instance = new SendNotifTxFactory();
        }

        return instance;
    }

    public UnspentOutputsBundle phase1(final int accountIdx) {

//        Log.i("accountIdx", "" + accountIdx);

        final String xpub;
        try {
            xpub = HD_WalletFactory.getInstance(context).get().getAccount(accountIdx).xpubstr();
        }
        catch(IOException ioe) {
            ioe.printStackTrace();
            return null;
        }
        catch(MnemonicException.MnemonicLengthException mle) {
            mle.printStackTrace();
            return null;
        }
//        Log.i("xpub", xpub);

        HashMap<String,List<String>> unspentOutputs = APIFactory.getInstance(context).getUnspentOuts();
        List<String> data = unspentOutputs.get(xpub);
        froms = new HashMap<String,String>();
        if(data != null)    {
            for(String f : data) {
                if(f != null) {
                    String[] s = f.split(",");
//                Log.i("address path", s[1] + " " + s[0]);
                    froms.put(s[1], s[0]);
                }
            }
        }

        UnspentOutputsBundle unspentCoinsBundle = null;
        try {
//            unspentCoinsBundle = getRandomizedUnspentOutputPoints(new String[]{xpub});

            ArrayList<String> addressStrings = new ArrayList<String>();
            addressStrings.add(xpub);
            for(String pcode : BIP47Meta.getInstance().getUnspentProviders())   {
                addressStrings.addAll(BIP47Meta.getInstance().getUnspentAddresses(context, pcode));
            }
            unspentCoinsBundle = getRandomizedUnspentOutputPoints(addressStrings.toArray(new String[addressStrings.size()]));

        }
        catch(Exception e) {
            return null;
        }
        if(unspentCoinsBundle == null || unspentCoinsBundle.getOutputs() == null) {
//                        Log.i("SpendThread", "allUnspent == null");
            return null;
        }

        return unspentCoinsBundle;
    }

    public Pair<Transaction, Long> phase2(final int accountIdx, final List<MyTransactionOutPoint> unspent, PaymentCode notifPcode) {

        sentChange = false;

        Pair<Transaction, Long> pair = null;

        try {
            int changeIdx = HD_WalletFactory.getInstance(context).get().getAccount(accountIdx).getChange().getAddrIdx();
            pair = makeTx(accountIdx, unspent, notifPcode, changeIdx);
        }
        catch(Exception e) {
            e.printStackTrace();
        }

        return pair;
    }

    public void phase3(final Pair<Transaction, Long> pair, final int accountIdx, final PaymentCode notifPcode, final OpCallback opc) {

        final Handler handler = new Handler();
        final HashMap<String,ECKey> keyBag = new HashMap<String,ECKey>();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Looper.prepare();

                    Transaction tx = pair.first;
                    Long priority = pair.second;

                    for (TransactionInput input : tx.getInputs()) {
                        byte[] scriptBytes = input.getOutpoint().getConnectedPubKeyScript();
                        String address = new BitcoinScript(scriptBytes).getAddress().toString();
//                        Log.i("address from script", address);
                        ECKey ecKey = null;
                        try {
                            String privStr = null;
                            String path = froms.get(address);
                            if(path == null)    {
                                String pcode = BIP47Meta.getInstance().getPCode4Addr(address);
                                int idx = BIP47Meta.getInstance().getIdx4Addr(address);
                                PaymentAddress addr = BIP47Util.getInstance(context).getReceiveAddress(new PaymentCode(pcode), idx);
                                ecKey = addr.getReceiveECKey();
                            }
                            else    {
                                String[] s = path.split("/");
                                HD_Address hd_address = AddressFactory.getInstance(context).get(accountIdx, Integer.parseInt(s[1]), Integer.parseInt(s[2]));
                                privStr = hd_address.getPrivateKeyString();
                                DumpedPrivateKey pk = new DumpedPrivateKey(MainNetParams.get(), privStr);
                                ecKey = pk.getKey();
                            }

                        } catch (AddressFormatException afe) {
                            afe.printStackTrace();
                            continue;
                        }

                        if(ecKey != null) {
                            keyBag.put(input.getOutpoint().toString(), ecKey);
                        }
                        else {
                            opc.onFail();
//                            Log.i("ECKey error", "cannot process private key");
                        }

                    }

                    signTx(tx, keyBag);
                    String hexString = new String(Hex.encode(tx.bitcoinSerialize()));
                    if(hexString.length() > (100 * 1024)) {
                        Toast.makeText(context, R.string.tx_length_error, Toast.LENGTH_SHORT).show();
//                        Log.i("SendFactory", "Transaction length too long");
                        opc.onFail();
                        throw new Exception(context.getString(R.string.tx_length_error));
                    }

//                    Log.i("SendFactory tx hash", tx.getHashAsString());
//                    Log.i("SendFactory tx string", hexString);

                    String response = PushTx.getInstance(context).groestlsight(hexString);

                    try {
                        //org.json.JSONObject jsonObject = new org.json.JSONObject(response);
                        if(response != null && (response.contains("txid") || response.length() == 68))    {
                            //if(jsonObject.getString("status").equals("ok"))    {
                                opc.onSuccess();
                                if(sentChange) {
                                    HD_WalletFactory.getInstance(context).get().getAccount(accountIdx).getChain(AddressFactory.CHANGE_CHAIN).incAddrIdx();
                                }

                                BIP47Meta.getInstance().setOutgoingIdx(notifPcode.toString(), 0);
//                        Log.i("SendNotifTxFactory", "tx hash:" + tx.getHashAsString());
                                BIP47Meta.getInstance().setOutgoingStatus(notifPcode.toString(), tx.getHashAsString(), BIP47Meta.STATUS_SENT_NO_CFM);

//                            SendAddressUtil.getInstance().add(notifPcode.notificationAddress().toString(), true);

                                HD_WalletFactory.getInstance(context).saveWalletToJSON(new CharSequenceX(AccessFactory.getInstance(context).getGUID() + AccessFactory.getInstance(context).getPIN()));
                            //}
                            //else {
                            //    Toast.makeText(context, jsonObject.getString("status"), Toast.LENGTH_SHORT).show();
                            //    opc.onFail();
                            //}
                        }
                        else    {
                            Toast.makeText(context, response, Toast.LENGTH_SHORT).show();
                            opc.onFail();
                        }
                    }
                    catch(JSONException je) {
                        Toast.makeText(context, je.getMessage(), Toast.LENGTH_SHORT).show();
                        opc.onFail();
                    }

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            ;
                        }
                    });

                    Looper.loop();

                }
                catch(Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private UnspentOutputsBundle getRandomizedUnspentOutputPoints(String[] from) throws Exception {

        BigInteger totalAmountPlusDust = _bNotifTxTotalAmount.add(SamouraiWallet.bDust);

        UnspentOutputsBundle ret = new UnspentOutputsBundle();

        HashMap<String,List<MyTransactionOutPoint>> outputsByAddress = new HashMap<String,List<MyTransactionOutPoint>>();

        ArrayList<String> addresses = getXPUB_unspent_addresses(from);

        String args = org.apache.commons.lang.StringUtils.join(addresses.toArray(), "|");

        Log.i("Unspent outputs url", WebUtil.BLOCKCHAIN_DOMAIN + "unspent?active=" + args);
        String response = WebUtil.getInstance(null).getURL(WebUtil.BLOCKCHAIN_DOMAIN_API + "unspent&active=" + args);
        Log.i("Unspent outputs", response);


        List<MyTransactionOutPoint> outputs = new ArrayList<MyTransactionOutPoint>();

        Map<String, Object> root = (Map<String, Object>)JSONValue.parse(response);
        List<Map<String, Object>> outputsRoot = (List<Map<String, Object>>)root.get("unspent_outputs");
        if(outputsRoot == null) {
//            Log.i("SendFactory", "JSON parse failed");
            return null;
        }
        boolean isChange = false;
        for (Map<String, Object> outDict : outputsRoot) {

            isChange = false;

            byte[] hashBytes = Hex.decode((String)outDict.get("tx_hash"));

            Hash hash = new Hash(hashBytes);
           // hash.reverse();
            Sha256Hash txHash = new Sha256Hash(hash.getBytes());

            int txOutputN = ((Number)outDict.get("tx_ouput_n")).intValue();
//            Log.i("Unspent output",  "n:" + txOutputN);
            BigInteger value = //BigInteger.valueOf(((Number)outDict.get("value")).longValue());
                    new BigInteger(outDict.get("value").toString(), 10);
//            Log.i("Unspent output",  "value:" + value.toString());
            byte[] scriptBytes = Hex.decode((String)outDict.get("script"));
            int confirmations = ((Number)outDict.get("confirmations")).intValue();
//            Log.i("Unspent output",  "confirmations:" + confirmations);

            String address = new BitcoinScript(scriptBytes).getAddress().toString();
            String path = null;
            if(outDict.containsKey("xpub")) {
                JSONObject obj = (JSONObject)outDict.get("xpub");
                if(obj.containsKey("path")) {
                    path = (String)obj.get("path");
                    froms.put(address, path);
                    String[] s = path.split("/");
                    if(s[1].equals("1")) {
                        isChange = true;
                    }
                }
            }

            // Construct the output
            MyTransactionOutPoint outPoint = new MyTransactionOutPoint(txHash, txOutputN, value, scriptBytes, address);
            outPoint.setConfirmations(confirmations);
            outPoint.setIsChange(isChange);
            outputs.add(outPoint);

            //
            // get all outputs from same public address
            //
            if(!outputsByAddress.containsKey(outPoint.getAddress())) {
                outputsByAddress.put(outPoint.getAddress(), new ArrayList<MyTransactionOutPoint>());
            }
            outputsByAddress.get(outPoint.getAddress()).add(outPoint);
            //
            //
            //

        }

        allOutputs = outputs;

        //
        // look for smallest UTXO that is >= totalAmount and return it
        //
        Collections.sort(outputs, new UnspentOutputAmountComparator());
        Collections.reverse(outputs);
        for (MyTransactionOutPoint output : outputs) {

//            Log.i("SendFactory", output.getValue().toString());

            if(output.getValue().compareTo(totalAmountPlusDust) >= 0) {
//                Log.i("SendFactory", "Single output:" + output.getAddress() + "," + output.getValue().toString());
                List<MyTransactionOutPoint> single_output = new ArrayList<MyTransactionOutPoint>();
                single_output.add(output);
                ret.setOutputs(single_output);
                ret.setChangeSafe(true);
                ret.setNbAddress(1);
                ret.setTotalAmount(output.getValue());
                ret.setType(UnspentOutputsBundle.SINGLE_OUTPUT);

                //
                // get all outputs from same public address
                //
                if(outputsByAddress.get(output.getAddress()).size() > 1) {
//                    Log.i("SendFactory", "Single address:" + output.getAddress() + "," + output.getValue().toString());
                    ret = new UnspentOutputsBundle();
                    List<MyTransactionOutPoint> same_address_outputs = new ArrayList<MyTransactionOutPoint>();
                    same_address_outputs.addAll(outputsByAddress.get(output.getAddress()));
                    ret.setOutputs(same_address_outputs);
                    ret.setChangeSafe(true);
                    ret.setNbAddress(same_address_outputs.size());
                    BigInteger total = BigInteger.ZERO;
                    for(MyTransactionOutPoint out : same_address_outputs) {
                        total = total.add(out.getValue());
                    }
                    ret.setTotalAmount(total);
                    ret.setType(UnspentOutputsBundle.SINGLE_ADDRESS);
                }
                //
                //
                //

                return ret;
            }

        }

        return null;
    }

    private Pair<Transaction,Long> makeTx(int accountIdx, List<MyTransactionOutPoint> unspent, PaymentCode notifPcode, int changeIdx) throws Exception {

        BigInteger amount = _bNotifTxValue.add(_bSWFee);

        long priority = 0;

        if(unspent == null || unspent.size() == 0) {
//			throw new InsufficientFundsException("No free outputs to spend.");
//            Log.i("SendFactory", "no unspents");
            return null;
        }

        List<TransactionOutput> outputs = new ArrayList<TransactionOutput>();

        //Construct a new transaction
        Transaction tx = new Transaction(MainNetParams.get());

        BigInteger outputValueSum = BigInteger.ZERO;

        outputValueSum = outputValueSum.add(_bNotifTxValue);
        BitcoinScript toOutputValueScript = BitcoinScript.createSimpleOutBitcoinScript(new BitcoinAddress(notifPcode.notificationAddress().getAddressString()));
        TransactionOutput outputValue = new TransactionOutput(MainNetParams.get(), null, Coin.valueOf(_bNotifTxValue.longValue()), toOutputValueScript.getProgram());
        outputs.add(outputValue);

        outputValueSum = outputValueSum.add(_bSWFee);
        BitcoinScript toOutputSWFeeScript = BitcoinScript.createSimpleOutBitcoinScript(new BitcoinAddress(SAMOURAI_NOTIF_TX_FEE_ADDRESS));
        TransactionOutput outputSWFee = new TransactionOutput(MainNetParams.get(), null, Coin.valueOf(_bSWFee.longValue()), toOutputSWFeeScript.getProgram());
        outputs.add(outputSWFee);

        BigInteger valueSelected = BigInteger.ZERO;
        BigInteger valueNeeded =  outputValueSum.add(_bFee);
        BigInteger minFreeOutputSize = BigInteger.valueOf(1000000);

        List<MyTransactionInput> inputs = new ArrayList<MyTransactionInput>();
        byte[] op_return = null;

        for(int i = 0; i < unspent.size(); i++) {

            MyTransactionOutPoint outPoint = unspent.get(i);

            BitcoinScript script = new BitcoinScript(outPoint.getScriptBytes());

            if(script.getOutType() == BitcoinScript.ScriptOutTypeStrange) {
                continue;
            }

            BitcoinScript inputScript = new BitcoinScript(outPoint.getConnectedPubKeyScript());
            String address = inputScript.getAddress().toString();
            MyTransactionInput input = new MyTransactionInput(MainNetParams.get(), null, new byte[0], outPoint, outPoint.getTxHash().toString(), outPoint.getTxOutputN());
            inputs.add(input);
            valueSelected = valueSelected.add(outPoint.getValue());
            priority += outPoint.getValue().longValue() * outPoint.getConfirmations();

            if(i == 0)    {
                ECKey ecKey = null;
                String privStr = null;
                String path = froms.get(address);
                if(path == null)    {
                    String pcode = BIP47Meta.getInstance().getPCode4Addr(address);
                    int idx = BIP47Meta.getInstance().getIdx4Addr(address);
                    PaymentAddress addr = BIP47Util.getInstance(context).getReceiveAddress(new PaymentCode(pcode), idx);
                    ecKey = addr.getReceiveECKey();
                }
                else    {
                    String[] s = path.split("/");
                    HD_Address hd_address = AddressFactory.getInstance(context).get(accountIdx, Integer.parseInt(s[1]), Integer.parseInt(s[2]));
                    privStr = hd_address.getPrivateKeyString();
                    DumpedPrivateKey pk = new DumpedPrivateKey(MainNetParams.get(), privStr);
                    ecKey = pk.getKey();
                }

                byte[] privkey = ecKey.getPrivKeyBytes();
                byte[] pubkey = notifPcode.notificationAddress().getPubKey();
                byte[] outpoint = outPoint.bitcoinSerialize();
//                Log.i("SendFactory", "outpoint:" + Hex.toHexString(outpoint));
//                Log.i("SendFactory", "payer shared secret:" + Hex.toHexString(new SecretPoint(privkey, pubkey).ECDHSecretAsBytes()));
                byte[] mask = notifPcode.getMask(new SecretPoint(privkey, pubkey).ECDHSecretAsBytes(), outpoint);
//                Log.i("SendFactory", "mask:" + Hex.toHexString(mask));
//                Log.i("SendFactory", "mask length:" + mask.length);
//                Log.i("SendFactory", "payload0:" + Hex.toHexString(BIP47Util.getInstance(context).getPaymentCode().getPayload()));
                op_return = PaymentCode.blind(BIP47Util.getInstance(context).getPaymentCode().getPayload(), mask);
//                Log.i("SendFactory", "payload1:" + Hex.toHexString(op_return));
            }

            if(valueSelected.compareTo(valueNeeded) == 0 || valueSelected.compareTo(valueNeeded.add(minFreeOutputSize)) >= 0) {
                break;
            }
        }

        //Check the amount we have selected is greater than the amount we need
        if(valueSelected.compareTo(valueNeeded) < 0) {
//			throw new InsufficientFundsException("Insufficient Funds");
//            Log.i("SendFactory", "valueSelected:" + valueSelected.toString());
//            Log.i("SendFactory", "valueNeeded:" + valueNeeded.toString());
            return null;
        }

        BigInteger change = valueSelected.subtract(outputValueSum).subtract(_bFee);
        if(change.compareTo(BigInteger.ZERO) == 1 && change.compareTo(SamouraiWallet.bDust) == -1)    {
            Toast.makeText(context, R.string.dust_change, Toast.LENGTH_SHORT).show();
            return null;
        }

        if(change.compareTo(BigInteger.ZERO) > 0) {

            try {
                HD_Address cAddr = HD_WalletFactory.getInstance(context).get().getAccount(accountIdx).getChange().getAddressAt(changeIdx);
                String changeAddr = cAddr.getAddressString();

                BitcoinScript change_script = null;
                if(changeAddr != null) {
                    change_script = BitcoinScript.createSimpleOutBitcoinScript(new BitcoinAddress(changeAddr));
                    TransactionOutput change_output = new TransactionOutput(MainNetParams.get(), null, Coin.valueOf(change.longValue()), change_script.getProgram());
                    outputs.add(change_output);
                }
                else {
                    throw new Exception(context.getString(R.string.invalid_tx_attempt));
                }
            }
            catch(Exception e) {
                ;
            }
        }
        else {
            sentChange = false;
        }

        //
        // deterministically sort inputs and outputs, see OBPP BIP proposal
        //
//        Collections.sort(inputs, new InputComparator());
        for(TransactionInput input : inputs) {
            tx.addInput(input);
        }

        tx.addOutput(Coin.valueOf(0L), new ScriptBuilder().op(ScriptOpCodes.OP_RETURN).data(op_return).build());
        Collections.sort(outputs, new OutputComparator());
        for(TransactionOutput to : outputs) {
            tx.addOutput(to);
        }

        //
        // calculate priority
        //
        long estimatedSize = tx.bitcoinSerialize().length + (114 * tx.getInputs().size());
        priority /= estimatedSize;

        return new Pair<Transaction, Long>(tx, priority);
    }

    //    private synchronized void signTx(Transaction transaction, Wallet wallet) throws ScriptException {
    private synchronized void signTx(Transaction transaction, HashMap<String,ECKey> keyBag) throws ScriptException {

        List<TransactionInput> inputs = transaction.getInputs();

        TransactionSignature[] sigs = new TransactionSignature[inputs.size()];
        ECKey[] keys = new ECKey[inputs.size()];
        for (int i = 0; i < inputs.size(); i++) {

            TransactionInput input = inputs.get(i);

            // Find the signing key
//            ECKey key = input.getOutpoint().getConnectedKey(wallet);
            ECKey key = keyBag.get(input.getOutpoint().toString());
            // Keep key for script creation step below
            keys[i] = key;
            byte[] connectedPubKeyScript = input.getOutpoint().getConnectedPubKeyScript();
            if(key.hasPrivKey() || key.isEncrypted()) {
                sigs[i] = transaction.calculateSignature(i, key, connectedPubKeyScript, SigHash.ALL, false);
            }
            else {
                sigs[i] = TransactionSignature.dummy();   // watch only ?
            }
        }

        for(int i = 0; i < inputs.size(); i++) {

            if(sigs[i] == null)   {
                continue;
            }

            TransactionInput input = inputs.get(i);
            final TransactionOutput connectedOutput = input.getOutpoint().getConnectedOutput();

            Script scriptPubKey = connectedOutput.getScriptPubKey();
            if(scriptPubKey.isSentToAddress()) {
                input.setScriptSig(ScriptBuilder.createInputScript(sigs[i], keys[i]));
            }
            else if(scriptPubKey.isSentToRawPubKey()) {
                input.setScriptSig(ScriptBuilder.createInputScript(sigs[i]));
            }
            else {
                throw new RuntimeException("Unknown script type: " + scriptPubKey);
            }
        }

    }

    private class UnspentOutputAmountComparator implements Comparator<MyTransactionOutPoint> {

        public int compare(MyTransactionOutPoint o1, MyTransactionOutPoint o2) {

            final int BEFORE = -1;
            final int EQUAL = 0;
            final int AFTER = 1;

            if(o1.getValue().compareTo(o2.getValue()) > 0) {
                return BEFORE;
            }
            else if(o1.getValue().compareTo(o2.getValue()) < 0) {
                return AFTER;
            }
            else    {
                return EQUAL;
            }

        }

    }

    private class InputComparator implements Comparator<MyTransactionInput> {

        public int compare(MyTransactionInput i1, MyTransactionInput i2) {

            final int BEFORE = -1;
            final int EQUAL = 0;
            final int AFTER = 1;

            Hash hash1 = new Hash(Hex.decode(i1.getTxHash()));
            Hash hash2 = new Hash(Hex.decode(i2.getTxHash()));
            byte[] h1 = hash1.getBytes();
            byte[] h2 = hash2.getBytes();

            int pos = 0;
            while(pos < h1.length && pos < h2.length)    {

                byte b1 = h1[pos];
                byte b2 = h2[pos];

                if((b1 & 0xff) < (b2 & 0xff))    {
                    return BEFORE;
                }
                else if((b1 & 0xff) > (b2 & 0xff))    {
                    return AFTER;
                }
                else    {
                    pos++;
                }

            }

            if(i1.getTxPos() < i2.getTxPos())    {
                return BEFORE;
            }
            else if(i1.getTxPos() > i2.getTxPos())    {
                return AFTER;
            }
            else    {
                return EQUAL;
            }

        }

    }

    private class OutputComparator implements Comparator<TransactionOutput> {

        public int compare(TransactionOutput o1, TransactionOutput o2) {

            final int BEFORE = -1;
            final int EQUAL = 0;
            final int AFTER = 1;

            if(o1.getValue().compareTo(o2.getValue()) > 0) {
                return AFTER;
            }
            else if(o1.getValue().compareTo(o2.getValue()) < 0) {
                return BEFORE;
            }
            else    {

                byte[] b1 = o1.getScriptBytes();
                byte[] b2 = o2.getScriptBytes();

                int pos = 0;
                while(pos < b1.length && pos < b2.length)    {

                    if((b1[pos] & 0xff) < (b2[pos] & 0xff))    {
                        return BEFORE;
                    }
                    else if((b1[pos] & 0xff) > (b2[pos] & 0xff))    {
                        return AFTER;
                    }

                    pos++;
                }

                if(b1.length < b2.length)    {
                    return BEFORE;
                }
                else if(b1.length > b2.length)    {
                    return AFTER;
                }
                else    {
                    return EQUAL;
                }

            }

        }

    }

    private synchronized ArrayList<String> getXPUB_unspent_addresses(String[] xpubs) {

        org.json.JSONObject jsonObject  = null;
        ArrayList<String> addresses = null;

        for(int i = 0; i < xpubs.length; i++)   {
            try {
                StringBuilder url = new StringBuilder(WebUtil.BLOCKCHAIN_DOMAIN_API);
                url.append("xpub2&xpub=");
                url.append(xpubs[i]);
                Log.i("APIFactory", "XPUB:" + url.toString());
                String response = WebUtil.getInstance(null).getURL(url.toString());
                Log.i("APIFactory", "XPUB response:" + response);
                try {
                    jsonObject = new org.json.JSONObject(response);

                    addresses = parseXPUB_unspent_addresses(jsonObject, xpubs[i]);
                }
                catch(JSONException je) {
                    je.printStackTrace();
                    jsonObject = null;
                }
            }
            catch(Exception e) {
                jsonObject = null;
                e.printStackTrace();
            }
        }

        return addresses;
    }
    private synchronized ArrayList<String> parseXPUB_unspent_addresses(org.json.JSONObject jsonObject, String xpub) throws JSONException  {


        if(jsonObject != null)  {
            ArrayList<String> addresses = new ArrayList<String>();
/*

            if(jsonObject.has("wallet"))  {
                JSONObject walletObj = (JSONObject)jsonObject.get("wallet");
                if(walletObj.has("final_balance"))  {
                    xpub_balance = walletObj.getLong("final_balance");
                }
            }
*/
            long latest_block = 0L;

            if(jsonObject.has("info"))  {
                org.json.JSONObject infoObj = (org.json.JSONObject)jsonObject.get("info");
                if(infoObj.has("latest_block"))  {
                    org.json.JSONObject blockObj = (org.json.JSONObject)infoObj.get("latest_block");
                    if(blockObj.has("height"))  {
                        latest_block = blockObj.getLong("height");
                    }
                }
            }

            if(jsonObject.has("addresses"))  {

                JSONArray addressesArray = (JSONArray)jsonObject.get("addresses");
                org.json.JSONObject addrObj = null;
                for(int i = 0; i < addressesArray.length(); i++)  {
                    addrObj = (org.json.JSONObject)addressesArray.get(i);
                    if(i == 1 && addrObj.has("n_tx") && addrObj.getInt("n_tx") > 0)  {
                    }
                    if(addrObj.has("final_balance") && addrObj.has("address"))  {
                    }
                }
            }

            if(jsonObject.has("txs"))  {

                JSONArray txArray = (JSONArray)jsonObject.get("txs");
                org.json.JSONObject txObj = null;
                for(int i = 0; i < txArray.length(); i++)  {

                    txObj = (org.json.JSONObject)txArray.get(i);
                    long height = 0L;
                    long amount = 0L;
                    long ts = 0L;
                    String hash = null;
                    String addr = null;
                    String _addr = null;
                    String path = null;
                    String input_xpub = null;
                    String output_xpub = null;
                    long move_amount = 0L;
                    long input_amount = 0L;
                    long output_amount = 0L;
                    long bip47_input_amount = 0L;
                    long xpub_input_amount = 0L;
                    long change_output_amount = 0L;
                    boolean hasBIP47Input = false;
                    boolean hasOnlyBIP47Input = true;
                    boolean hasChangeOutput = false;

                    if(txObj.has("block_height"))  {
                        height = txObj.getLong("block_height");
                    }
                    else  {
                        height = -1L;  // 0 confirmations
                    }
                    if(txObj.has("hash"))  {
                        hash = (String)txObj.get("hash");
                    }
                    if(txObj.has("result"))  {
                        amount = txObj.getLong("result");
                    }
                    if(txObj.has("time"))  {
                        ts = txObj.getLong("time");
                    }

                    if(txObj.has("inputs"))  {
                        JSONArray inputArray = (JSONArray)txObj.get("inputs");
                        org.json.JSONObject inputObj = null;
                        for(int j = 0; j < inputArray.length(); j++)  {
                            inputObj = (org.json.JSONObject)inputArray.get(j);
                            if(inputObj.has("prev_out"))  {
                                org.json.JSONObject prevOutObj = (org.json.JSONObject)inputObj.get("prev_out");
                                input_amount += prevOutObj.getLong("value");
                                if(prevOutObj.has("xpub"))  {
                                    org.json.JSONObject xpubObj = (org.json.JSONObject)prevOutObj.get("xpub");
                                    addr = (String)xpubObj.get("m");
                                    input_xpub = addr;
                                    xpub_input_amount -= prevOutObj.getLong("value");
                                    hasOnlyBIP47Input = false;
                                }
                                else if(prevOutObj.has("addr") && BIP47Meta.getInstance().getPCode4Addr(prevOutObj.getString("addr")) != null)  {
                                    hasBIP47Input = true;
                                    bip47_input_amount -= prevOutObj.getLong("value");
                                }
                                else if(prevOutObj.has("addr") && BIP47Meta.getInstance().getPCode4Addr(prevOutObj.getString("addr")) == null)  {
                                    hasOnlyBIP47Input = false;
                                }
                                else  {
                                    _addr = (String)prevOutObj.get("addr");
                                }
                            }
                        }
                    }

                    if(txObj.has("out"))  {
                        JSONArray outArray = (JSONArray)txObj.get("out");
                        org.json.JSONObject outObj = null;
                        for(int j = 0; j < outArray.length(); j++)  {
                            outObj = (org.json.JSONObject)outArray.get(j);
                            output_amount += outObj.getLong("value");
                            if(outObj.has("xpub"))  {
                                org.json.JSONObject xpubObj = (org.json.JSONObject)outObj.get("xpub");
                                //addr = (String)xpubObj.get("m");
                                addr = xpub;
                                change_output_amount += outObj.getLong("value");
                                path = xpubObj.getString("path");
                                if(outObj.has("spent"))  {
                                    if(outObj.getBoolean("spent") == false && outObj.has("addr"))  {
                                        if(!addresses.contains(outObj.getString("addr")))
                                            addresses.add(outObj.getString("addr"));
                                        //froms.put(outObj.getString("addr"), path);
                                    }
                                }
                                if(input_xpub != null && !input_xpub.equals(addr))    {
                                    output_xpub = addr;
                                    move_amount = outObj.getLong("value");
                                }
                            }
                            else  {
                                _addr = (String)outObj.get("addr");
                            }
                        }

                        if(hasOnlyBIP47Input && !hasChangeOutput)    {
                            amount = bip47_input_amount;
                        }
                        else if(hasBIP47Input)    {
                            amount = bip47_input_amount + xpub_input_amount + change_output_amount;
                        }
                        else    {
                            ;
                        }

                    }

                    if(addr != null)  {

                        //
                        // test for MOVE from Shuffling -> Samourai account
                        //


                    }
                }

            }
            return addresses;
        }
        return null;
    }

}
