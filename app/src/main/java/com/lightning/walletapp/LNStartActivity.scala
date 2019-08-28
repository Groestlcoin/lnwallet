package com.lightning.walletapp

import spray.json._
import android.view._
import android.widget._
import android.support.v4.app._
import com.lightning.walletapp.ln._
import com.lightning.walletapp.Utils._
import com.lightning.walletapp.ln.wire._
import com.lightning.walletapp.R.string._
import com.lightning.walletapp.ln.Tools._
import com.github.kevinsawicki.http.HttpRequest._
import com.lightning.walletapp.lnutils.ImplicitConversions._
import com.lightning.walletapp.lnutils.olympus.OlympusWrap._
import com.lightning.walletapp.lnutils.ImplicitJsonFormats._
import com.lightning.walletapp.Utils.app.TransData.nodeLink
import com.lightning.walletapp.helper.ThrottledWork
import fr.acinq.bitcoin.Crypto.PublicKey
import org.bitcoinj.uri.BitcoinURI
import scodec.bits.ByteVector
import android.os.Bundle
import scala.util.Try


class LNStartActivity extends ScanActivity { me =>
  lazy val slidingFragmentAdapter = new FragmentStatePagerAdapter(getSupportFragmentManager) {
    def getItem(currentFragmentPos: Int) = if (0 == currentFragmentPos) new FragLNStart else new FragScan
    def getCount = 2
  }

  override def onBackPressed = {
    val isScannerOpen = 1 == walletPager.getCurrentItem
    if (isScannerOpen) walletPager.setCurrentItem(0, true)
    else super.onBackPressed
  }

  override def onOptionsItemSelected(m: MenuItem) = runAnd(true) {
    if (m.getItemId == R.id.actionScan) walletPager.setCurrentItem(1, true)
  }

  override def onResume = wrap(super.onResume)(me returnToBase null)
  override def onCreateOptionsMenu(menu: Menu) = runAnd(true) {
    // Called after FragLNStart sets its toolbar as actionbar
    getMenuInflater.inflate(R.menu.lnstart, menu)
    FragLNStart.fragment.setupSearch(menu)
  }

  def INIT(s: Bundle) = if (app.isAlive) {
    me setContentView R.layout.activity_double_pager
    walletPager setAdapter slidingFragmentAdapter
  } else me exitTo classOf[MainActivity]

  def checkTransData =
    app.TransData checkAndMaybeErase {
      case _: LNUrl => me exitTo MainActivity.wallet
      case _: BitcoinURI => me exitTo MainActivity.wallet
      case _: PaymentRequest => me exitTo MainActivity.wallet
      case _: NodeAnnouncement => me goTo classOf[LNStartFundActivity]
      case _ => me returnToBase null
    }
}

object FragLNStart {
  var fragment: FragLNStart = _
}

class FragLNStart extends Fragment with SearchBar with HumanTimeDisplay { me =>
  override def onCreateView(inf: LayoutInflater, vg: ViewGroup, bn: Bundle) = inf.inflate(R.layout.frag_ln_start, vg, false)
  val testnet = BuildConfig.APPLICATION_ID.contains("testnet")

  //Testnet
  val testGRSPayKey = PublicKey.fromValidHex("0384dee0ec597a7b8235ccf56c68ffa0af5dae72b3455aa3ecb81c4fc4eef9ef2c")
  val testGRSPayNa = app.mkNodeAnnouncement(testGRSPayKey, NodeAddress.fromParts("95.179.140.39", 9735), "testnet grspay")
  val testGRSPay = HardcodedNodeView(testGRSPayNa, "<i>testnet.grspay.com</i>")

  val testElectrumKey = PublicKey.fromValidHex("02435dea09ad875c36c88f680845277245e7e8bdd28b3bb20470e82e4de0c3cb09")
  val testElectrumNa = app.mkNodeAnnouncement(testElectrumKey, NodeAddress.fromParts("45.32.236.128", 9735), "electrum-test1")
  val testElectrumPay = HardcodedNodeView(testElectrumNa, "<i>electrum-test1.groestlcoin.org</i>")

  val testLNKey = PublicKey.fromValidHex("03397476b50dae183eed13537b01a303991462bea35b08afb4232e19ae5fa78a2e")
  val testLNNa = app.mkNodeAnnouncement(testLNKey, NodeAddress.fromParts("45.32.235.71", 9735), "lntestnet")
  val testLNPay = HardcodedNodeView(testLNNa, "<i>lntestnet.groestlcoin.org</i>")

  val testOlympus1Key = PublicKey.fromValidHex("024d7eff7ab880adf0c69384453e999c01e1b10d6d22c1b52f9ba01e613d40e502")
  val testOlympus1Na = app.mkNodeAnnouncement(testOlympus1Key, NodeAddress.fromParts("95.179.156.115", 9196), "olympus-test1")
  val testOlympus1 = HardcodedNodeView(testOlympus1Na, "<i>olympus-test1.groestlcoin.org</i>")

  val testOlympus2Key = PublicKey.fromValidHex("021fedfc02b43971339bf9052e2c639e182be6565435d1606761718352be666f15")
  val testOlympus2Na = app.mkNodeAnnouncement(testOlympus2Key, NodeAddress.fromParts("108.61.99.169", 9196), "olympus-test2")
  val testOlympus2 = HardcodedNodeView(testOlympus2Na, "<i>olympus-test2.groestlcoin.org</i>")

  //Mainnet
  val GRSPayKey = PublicKey.fromValidHex("0391c8d0e27fe61ed8cb8784aeae5848bd8b193ea5720dea32ca2694a326fe41f9")
  val GRSPayNa = app.mkNodeAnnouncement(GRSPayKey, NodeAddress.fromParts("104.236.133.196", 9735), "grspay")
  val GRSPay = HardcodedNodeView(GRSPayNa, "<i>grspay.com</i>")

  val LNKey = PublicKey.fromValidHex("03046e1650b0e67925d260f4888f809598af6cef58fbfc6446fbd4fddf1828ca3d")
  val LNNa = app.mkNodeAnnouncement(LNKey, NodeAddress.fromParts("104.236.130.222", 9735), "lnmainnet")
  val LNPay = HardcodedNodeView(LNNa, "<i>lnmainnet.groestlcoin.org</i>")

  val olympus1Key = PublicKey.fromValidHex("0226cbef3bef64405046de9fb182acb0fd344e535b524c6e98d2a9131235b8390b")
  val olympus1Na = app.mkNodeAnnouncement(olympus1Key, NodeAddress.fromParts("82.196.11.189", 9196), "olympus1")
  val olympus1 = HardcodedNodeView(olympus1Na, "<i>olympus1.groestlcoin.org</i>")

  val olympus2Key = PublicKey.fromValidHex("02576fe2dfc26879c751a38f69a1e6b6d6646fa3edf045d5534d8674a188c7da81")
  val olympus2Na = app.mkNodeAnnouncement(olympus2Key, NodeAddress.fromParts("82.196.13.206", 9176), "olympus2")
  val olympus2 = HardcodedNodeView(olympus2Na, "<i>olympus2.groestlcoin.org</i>")

  val hardcodedNodes = if(!testnet) Vector(GRSPay, LNPay, olympus1, olympus2) else Vector(testGRSPay, testElectrumPay, testLNPay, testOlympus1, testOlympus2)

  lazy val host = me.getActivity.asInstanceOf[LNStartActivity]
  private[this] var nodes = Vector.empty[StartNodeView]
  FragLNStart.fragment = me

  val worker = new ThrottledWork[String, AnnounceChansNumVec] {
    def work(nodeSearchAsk: String) = app.olympus findNodes nodeSearchAsk
    def error(nodeSearchError: Throwable) = host onFail nodeSearchError

    def process(userQuery: String, results: AnnounceChansNumVec) = {
      val remoteNodeViewWraps = for (nodeInfo <- results) yield RemoteNodeView(nodeInfo)
      nodes = if (userQuery.isEmpty) hardcodedNodes ++ remoteNodeViewWraps else remoteNodeViewWraps
      host.UITask(adapter.notifyDataSetChanged).run
    }
  }

  val adapter = new BaseAdapter {
    def getView(pos: Int, savedView: View, par: ViewGroup) = {
      val slot = host.getLayoutInflater.inflate(R.layout.frag_single_line, null)
      val textLine = slot.findViewById(R.id.textLine).asInstanceOf[TextView]
      val txt = getItem(pos).asString(app getString ln_ops_start_node_view)
      textLine setText txt.html
      slot
    }

    def getItem(position: Int) = nodes(position)
    def getItemId(position: Int) = position
    def getCount = nodes.size
  }

  def react = worker addWork lastQuery
  def onNodeSelected(pos: Int): Unit = {
    app.TransData.value = adapter getItem pos
    host goTo classOf[LNStartFundActivity]
  }

  override def onViewCreated(view: View, state: Bundle) = if (app.isAlive) {
    val lnStartNodesList = view.findViewById(R.id.lnStartNodesList).asInstanceOf[ListView]
    me initToolbar view.findViewById(R.id.toolbar).asInstanceOf[android.support.v7.widget.Toolbar]
    wrap(host.getSupportActionBar setTitle action_ln_open)(host.getSupportActionBar setSubtitle ln_status_peer)
    lnStartNodesList setOnItemClickListener host.onTap(onNodeSelected)
    lnStartNodesList setAdapter adapter
    host.checkTransData
    react
  }
}

// DISPLAYING NODES ON UI

sealed trait StartNodeView {
  def asString(base: String): String
}

case class IncomingChannelParams(nodeView: HardcodedNodeView, open: OpenChannel)
case class HardcodedNodeView(ann: NodeAnnouncement, tip: String) extends StartNodeView {
  // App suggests a bunch of hardcoded and separately fetched nodes with a good liquidity
  def asString(base: String) = base.format(ann.alias, tip, ann.pretty)
}

case class RemoteNodeView(acn: AnnounceChansNum) extends StartNodeView {
  def asString(base: String) = base.format(ca.alias, app.plur1OrZero(chansNumber, num), ca.pretty)
  lazy val chansNumber = app.getResources getStringArray R.array.ln_ops_start_node_channels
  val ca \ num = acn
}

// LNURL response types

object LNUrlData {
  type PayReqVec = Vector[PaymentRequest]
  def guardResponse(raw: String): String = {
    val validJson = Try(raw.parseJson.asJsObject.fields)
    val hasError = validJson.map(_ apply "reason").map(json2String)
    if (validJson.isFailure) throw new Exception(s"Invalid response $raw")
    if (hasError.isSuccess) throw new Exception(hasError.get)
    raw
  }
}

sealed trait LNUrlData {
  def unsafe(request: String) = get(request, true).trustAllCerts.trustAllHosts.body
  require(callback contains "https://", "Callback does not have HTTPS prefix")
  val callback: String
}

case class WithdrawRequest(callback: String, k1: String,
                           maxWithdrawable: Long, defaultDescription: String,
                           minWithdrawable: Option[Long] = None) extends LNUrlData {

  val minCanReceive = minWithdrawable getOrElse 1L
  require(minCanReceive >= 1L, "minCanReceive is too low")
  require(minCanReceive <= maxWithdrawable, "minCanReceive is too high")
}

case class IncomingChannelRequest(uri: String, callback: String, k1: String) extends LNUrlData {
  def resolveAnnounce = app.mkNodeAnnouncement(PublicKey(ByteVector fromValidHex key), NodeAddress.fromParts(host, port.toInt), host)
  def requestChannel = unsafe(s"$callback?k1=$k1&remoteid=${LNParams.nodePublicKey.toString}&private=1")
  val nodeLink(key, host, port) = uri
}