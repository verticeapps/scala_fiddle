package scalafiddle.client.component

import diode.{Action, ModelR, ModelRO, NoAction}
import diode.data.{Pot, Ready}
import diode.react.ModelProxy
import japgolly.scalajs.react._
import org.scalajs.dom
import org.scalajs.dom.raw.{Event, HTMLElement, HTMLIFrameElement, MouseEvent}

import scala.concurrent.ExecutionContext.Implicits.{global => ecGlobal}
import scala.concurrent.{Future, Promise}
import scala.scalajs.js
import scala.scalajs.js.Dynamic._
import scala.scalajs.js.{Dynamic => Dyn}
import scala.util.Success
import scala.util.matching.Regex
import scalafiddle.client._

object FiddleEditor {
  import japgolly.scalajs.react.vdom.Implicits._
  import SemanticUI._

  val editorRef = Ref[dom.raw.HTMLDivElement]("editor")
  val resultRef = Ref[dom.raw.HTMLIFrameElement]("result")
  val optionsMenuRef = Ref[HTMLElement]("optionsMenu")

  case class EditorBinding(name: String, keys: String, action: () => Any)

  case class Props(data: ModelProxy[Pot[FiddleData]], fiddleId: Option[FiddleId], compilerData: ModelR[AppModel, CompilerData]) {
    def dispatch = data.theDispatch
  }

  case class State(
    compilerData: CompilerData,
    showTemplate: Boolean = false,
    preCode: List[String] = Nil,
    mainCode: List[String] = Nil,
    postCode: List[String] = Nil,
    indent: Int = 0
  )

  case class Backend($: BackendScope[Props, State]) {
    var unsubscribe: () => Unit = () => ()
    var editor: Dyn = _
    var resultFrame: HTMLIFrameElement = _

    def render(props: Props, state: State) = {
      import japgolly.scalajs.react.vdom.all._
      div(cls := "full-screen")(
        header(
          div(cls := "logo")(
            a(href := "#")(
              img(src := "/assets/images/scalafiddle-logo.png", alt := "ScalaFiddle")
            )
          ),
          div(cls := "ui basic button", onClick --> props.dispatch(compile(reconstructSource(state), FastOpt)))(Icon.play, "Run"),
          div(cls := "ui basic button")(Icon.pencil, "Save"),
          div(cls := "ui basic button")(Icon.codeFork, "Fork"),
          div(cls := "ui basic button")("Embed", Icon.caretDown)
        ),
        div(cls := "main")(
          Sidebar(props.data),
          div(cls := "editor-area")(
            div(cls := "editor")(
              div(cls := "optionsmenu")(
                Dropdown("top right pointing mini button", span("SCALA", i(cls := "icon setting")))(
                  div(cls := "header")("Options"),
                  div(cls := "divider"),
                  div(cls := "ui input")(
                    div(cls := "ui checkbox")(
                      input.checkbox(checked := state.showTemplate, onChange --> switchTemplate),
                      label("Show template")
                    )
                  )
                )
              ),
              div(id := "editor", ref := editorRef)
            ),
            div(cls := "output")(
              div(cls := "label", state.compilerData.status.show),
              iframe(
                id := "resultframe",
                ref := resultRef,
                width := "100%",
                height := "100%",
                frameBorder := "0",
                sandbox := "allow-scripts",
                src := s"resultframe?theme=light")
            )
          )
        )
      )
    }

    def sendFrameCmd(cmd: String, data: String = "") = {
      val msg = js.Dynamic.literal(cmd = cmd, data = data)
      resultFrame.contentWindow.postMessage(msg, "*")
    }

    def complete(): Unit = {
      editor.completer.showPopup(editor)
      // needed for firefox on mac
      editor.completer.cancelContextMenu()
    }

    def beginCompilation(): Future[Unit] = {
      // fully clear the code iframe by reloading it
      val p = Promise[Unit]
      resultFrame.onload = (e: Event) => p.complete(Success(()))
      resultFrame.src = resultFrame.src
      p.future
    }

    def compile(source: String, opt: SJSOptimization): Action = {
      CompileFiddle(source, opt)
    }

    def mounted(refs: RefsObject, props: Props): Callback = {
      import JsVal.jsVal2jsAny

      Callback {
        resultFrame = ReactDOM.findDOMNode(refs(resultRef).get)
        // create the Ace editor and configure it
        val Autocomplete = global.require("ace/autocomplete").Autocomplete
        val completer = Dyn.newInstance(Autocomplete)()
        editor = global.ace.edit(ReactDOM.findDOMNode(refs(editorRef).get))

        editor.setTheme("ace/theme/eclipse")
        editor.getSession().setMode("ace/mode/scala")
        editor.getSession().setTabSize(2)
        editor.setShowPrintMargin(false)
        editor.getSession().setOption("useWorker", false)
        editor.updateDynamic("completer")(completer) // because of SI-7420
        editor.updateDynamic("$blockScrolling")(Double.PositiveInfinity)

        val bindings = Seq(
          EditorBinding("Compile", "Enter",
            () => beginCompilation().foreach(_ => props.data.dispatch(compile(reconstructSource($.accessDirect.state), FastOpt)).runNow())),
          EditorBinding("FullOptimize", "Shift-Enter",
            () => beginCompilation().foreach(_ => props.data.dispatch(compile(reconstructSource($.accessDirect.state), FullOpt)).runNow())),
          EditorBinding("Save", "S", () => NoAction),
          EditorBinding("Complete", "Space", () => complete())
        )
        for (EditorBinding(name, key, func) <- bindings) {
          val binding = s"Ctrl-$key|Cmd-$key"
          editor.commands.addCommand(JsVal.obj(
            "name" -> name,
            "bindKey" -> JsVal.obj(
              "win" -> binding,
              "mac" -> binding,
              "sender" -> "editor|cli"
            ),
            "exec" -> func
          ))
        }

        editor.completers = js.Array(JsVal.obj(
          "getCompletions" -> { (editor: Dyn, session: Dyn, pos: Dyn, prefix: Dyn, callback: Dyn) => {
            def applyResults(results: Seq[(String, String)]): Unit = {
              val aceVersion = results.map { case (name, value) =>
                JsVal.obj(
                  "value" -> value,
                  "caption" -> (value + name)
                ).value
              }
              callback(null, js.Array(aceVersion: _*))
            }
            // dispatch an action to fetch completion results
            props.data.dispatch(AutoCompleteFiddle(
              session.getValue().asInstanceOf[String],
              pos.row.asInstanceOf[Int],
              pos.column.asInstanceOf[Int],
              applyResults)
            ).runNow()
          }
          }
        ).value)

        // apply Semantic UI
        // JQueryStatic(".ui.checkbox").checkbox()

        // subscribe to changes in compiler data
        unsubscribe = AppCircuit.subscribe(props.compilerData)(compilerDataUpdated)
      } >> updateFiddle(props.data())
    }

    val fiddleStart = """\s*// \$FiddleStart\s*$""".r
    val fiddleEnd = """\s*// \$FiddleEnd\s*$""".r

    // separate source code into pre,main,post blocks
    def extractCode(src: String): (List[String], List[String], List[String]) = {
      val lines = src.split("\n")
      val (pre, main, post) = lines.foldLeft((List.empty[String], List.empty[String], List.empty[String])) {
        case ((preList, mainList, postList), line) => line match {
          case fiddleStart() =>
            (line :: mainList ::: preList, Nil, Nil)
          case fiddleEnd() if preList.nonEmpty =>
            (preList, mainList, line :: postList)
          case l if postList.nonEmpty =>
            (preList, mainList, line :: postList)
          case _ =>
            (preList, line :: mainList, postList)
        }
      }
      (pre.reverse, main.reverse, post.reverse)
    }

    def updateFiddle(fiddlePot: Pot[FiddleData]): Callback = {
      fiddlePot match {
        case Ready(fiddle) =>
          val (pre, main, post) = extractCode(fiddle.origSource)
          $.state.flatMap { state =>
            if (state.showTemplate) {
              editor.getSession().setValue((pre ++ main ++ post).mkString("\n"))
              $.setState(state.copy(preCode = pre, mainCode = main, postCode = post, indent = 0))
            } else {
              // figure out indentation
              val indent = main.filter(_.nonEmpty).map(_.takeWhile(_ == ' ').length).min
              editor.getSession().setValue(main.map(_.drop(indent)).mkString("\n"))
              $.setState(state.copy(preCode = pre, mainCode = main, postCode = post, indent = indent))
            }
          }
        case _ =>
          Callback.empty
      }
    }

    def reconstructSource(state: State): String = {
      val editorContent = editor.getSession().getValue().asInstanceOf[String]
      if (state.showTemplate) {
        editorContent
      } else {
        val reIndent = " " * state.indent
        val newSource = editorContent.split("\n").map(reIndent + _)
        (state.preCode ++ newSource ++ state.postCode).mkString("\n")
      }
    }

    def unmounted: Callback = {
      Callback(unsubscribe())
    }

    def switchTemplate: Callback = {
      $.modState { s =>
        val row = editor.getCursorPosition().row.asInstanceOf[Int]
        val col = editor.getCursorPosition().column.asInstanceOf[Int]
        val source = reconstructSource(s)
        val (pre, main, post) = extractCode(source)
        if (!s.showTemplate) {
          editor.getSession().setValue((pre ++ main ++ post).mkString("\n"))
          editor.moveCursorTo(row + pre.size, col + s.indent)
          s.copy(preCode = pre, mainCode = main, postCode = post, indent = 0, showTemplate = !s.showTemplate)
        } else {
          // figure out indentation
          val indent = main.filter(_.nonEmpty).map(_.takeWhile(_ == ' ').length).min
          editor.getSession().setValue(main.map(_.drop(indent)).mkString("\n"))
          editor.moveCursorTo(math.max(0, row - pre.size), math.max(0, col - indent))
          s.copy(preCode = pre, mainCode = main, postCode = post, indent = indent, showTemplate = !s.showTemplate)
        }
      }
    }

    def clearResult() = sendFrameCmd("clear")

    def compilerDataUpdated(data: ModelRO[CompilerData]): Unit = {
      import scala.scalajs.js.JSConverters._
      val compilerData = data()
      clearResult()

      // show error messages, if any
      editor.getSession().clearAnnotations()
      if (compilerData.annotations.nonEmpty) {
        val aceAnnotations = compilerData.annotations.map { ann =>
          JsVal.obj(
            "row" -> ann.row,
            "col" -> ann.col,
            "text" -> ann.text.mkString("\n"),
            "type" -> ann.tpe
          ).value
        }.toJSArray
        editor.getSession().setAnnotations(aceAnnotations)

        // show compiler errors in output
        val allErrors = compilerData.annotations.map { ann =>
          s"ScalaFiddle.scala:${ann.row + 1}: ${ann.tpe}: ${ann.text.mkString("\n")}"
        }.mkString("\n")

        sendFrameCmd("print", s"""<pre class="error">$allErrors</pre>""")
      }

      compilerData.jsCode.foreach { jsCode =>
        sendFrameCmd("code", jsCode)
      }
      $.modState(s => s.copy(compilerData = compilerData)).runNow()
    }
  }

  val component = ReactComponentB[Props]("FiddleEditor")
    .initialState_P(props => State(props.compilerData()))
    .renderBackend[Backend]
    .componentDidMount(scope => scope.backend.mounted(scope.refs, scope.props))
    .componentWillUnmount(scope => scope.backend.unmounted)
    .componentWillReceiveProps(scope => scope.$.backend.updateFiddle(scope.nextProps.data()))
    .build

  def apply(data: ModelProxy[Pot[FiddleData]], fiddleId: Option[FiddleId], compilerData: ModelR[AppModel, CompilerData]) =
    component(Props(data, fiddleId, compilerData))
}