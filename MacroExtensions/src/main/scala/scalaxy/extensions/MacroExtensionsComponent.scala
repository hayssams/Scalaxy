// Author: Olivier Chafik (http://ochafik.com)
// Feel free to modify and reuse this for any purpose ("public domain / I don't care").
package scalaxy.extensions

import scala.collection.mutable

import scala.reflect.internal._
import scala.reflect.ClassTag

import scala.tools.nsc.CompilerCommand
import scala.tools.nsc.Global
import scala.tools.nsc.Phase
import scala.tools.nsc.plugins.Plugin
import scala.tools.nsc.plugins.PluginComponent
import scala.tools.nsc.Settings
import scala.tools.nsc.reporters.ConsoleReporter
import scala.tools.nsc.transform.TypingTransformers

/**
 *  To understand / reproduce this, you should use paulp's :power mode in the scala console:
 *
 *  scala
 *  > :power
 *  > :phase parser // will show us ASTs just after parsing
 *  > val Some(List(ast)) = intp.parse("@scalaxy.extend(Int) def str = self.toString")
 *  > nodeToString(ast)
 *  > val DefDef(mods, name, tparams, vparamss, tpt, rhs) = ast // play with extractors to explore the tree and its properties.
 */
class MacroExtensionsComponent(val global: Global, macroExtensions: Boolean = true, runtimeExtensions: Boolean = false)
    extends PluginComponent
    with TypingTransformers
    with Extensions
{
  import global._
  import definitions._

  override val phaseName = "scalaxy-extensions"

  override val runsRightAfter = Some("parser")
  override val runsAfter = runsRightAfter.toList
  override val runsBefore = List[String]("namer")

  private final val selfName = "self"


  def newPhase(prev: Phase): StdPhase = new StdPhase(prev) {
    def apply(unit: CompilationUnit) {
      val onTransformer = new Transformer
      {
        object ExtendAnnotation {
          object ExtendAnnotationName {
            def unapply(tpt: Tree) = Option(tpt.toString) collect {
              case "extend" =>
                true
              case "scalaxy.extend" =>
                false
            }
          }
          def unapply(tree: Tree) = Option(tree) collect {
            case Apply(Select(New(ExtendAnnotationName(deprecated)), initName), List(targetValueTpt))
            if initName == nme.CONSTRUCTOR =>
              if (deprecated)
                unit.error(tree.pos, "Please use `@scalaxy.extend` instead of `@extend`")
              targetValueTpt
            case _ =>
              println(nodeToString(tree))
              null
          }
        }
        def banVariableNames(names: Set[String], root: Tree) {
          val banTraverser = new Traverser {
            override def traverse(tree: Tree) = {
              tree match {
                case d: DefTree if names.contains(Option(d.name).map(_.toString).getOrElse("")) =>
                  unit.error(tree.pos, s"Cannot redefine name ${d.name}")
                case _ =>
              }
              super.traverse(tree)
            }
          }
          banTraverser.traverse(root)
        }

        def newExtensionName(name: Name) =
          unit.fresh.newName("scalaxy$extensions$" + name + "$")

        // Tranforms a value tree (as found in annotation values) to a type tree.
        def typify(valueTpt: Tree): Tree = valueTpt match {
          case Ident(n) =>
            Ident(n.toString: TypeName)
          case TypeApply(target, args) =>
            AppliedTypeTree(
              typify(target),
              args.map(typify(_))
            )
          case _ =>
            unit.error(valueTpt.pos, "Type not handled yet: " + nodeToString(valueTpt) + ": " + valueTpt.getClass.getName)
            null
        }

        def getTypeNames(tpt: Tree): Seq[TypeName] = {
          val res = mutable.ArrayBuffer[TypeName]()
          new Traverser { override def traverse(tree: Tree) = tree match {
            case Ident(n: TypeName) => res += n
            case _ => super.traverse(tree)
          }}.traverse(tpt)
          res.result()
        }

        def transformMacroExtension(tree: DefDef): List[Tree] =
        {
          val DefDef(Modifiers(flags, privateWithin, annotations), name, tparams, vparamss, tpt, rhs) = tree
          val extendAnnotationOpt = annotations.find(ExtendAnnotation.unapply(_) != None)
          extendAnnotationOpt match
          {
            case Some(extendAnnotation @ ExtendAnnotation(targetValueTpt)) =>
              if (tpt.isEmpty)
                unit.error(tree.pos, "Macro extensions require explicit return type annotation")

              val extensionName = newExtensionName(name)
              val targetTpt = typify(targetValueTpt)
              val typeNamesInTarget = getTypeNames(targetTpt).toSet
              val (outerTParams, innerTParams) =
                tparams.partition { 
                  case tparam @ TypeDef(_, tname, _, _) => 
                    typeNamesInTarget.contains(tname) 
                }
              
              val selfTreeName: TermName = unit.fresh.newName("selfTree")
              // Don't rename the context, otherwise explicit macros are hard to write.
              val contextName: TermName = "c" //unit.fresh.newName("c")

              val expressionNames = (vparamss.flatten.map(_.name.toString) :+ selfName.toString).toSet
              banVariableNames(expressionNames, rhs)
              List(
                newImportMacros(tree.pos),
                ClassDef(
                  Modifiers((flags | Flag.IMPLICIT) -- Flag.MACRO, privateWithin, Nil),
                  extensionName: TypeName,
                  outerTParams,
                  Template(
                    List(parentTypeTreeForImplicitWrapper(targetTpt.toString: TypeName)),
                    newSelfValDef(),
                    genParamAccessorsAndConstructor(
                      List(selfName -> targetTpt)
                    ) :+
                    // Copying the original def over, without its @scalaxy.extend annotation.
                    DefDef(
                      Modifiers(flags | Flag.MACRO, privateWithin, annotations.filter(_ ne extendAnnotation)),
                      name,
                      innerTParams,
                      vparamss,
                      tpt,
                      {
                        val macroPath = termPath(extensionName + "." + name)
                        if (tparams.isEmpty)
                          macroPath
                        else
                          TypeApply(
                            macroPath,
                            tparams.map {
                              case TypeDef(_, tname, _, _) =>
                                Ident(tname.toString: TypeName)
                            }
                          )
                      }
                    )
                  )
                ),
                ModuleDef(
                  NoMods,
                  extensionName,
                  Template(
                    List(typePath("scala.AnyRef")),
                    newSelfValDef(),
                    genParamAccessorsAndConstructor(Nil) :+
                    DefDef(
                      NoMods,
                      name,
                      tparams, // TODO map T => T : c.WeakTypeTag
                      List(
                        List(
                          ValDef(Modifiers(Flag.PARAM), contextName, typePath("scala.reflect.macros.Context"), EmptyTree)
                        )
                      ) ++
                      (
                        if (vparamss.flatten.isEmpty)
                          Nil
                        else
                          List(
                            vparamss.flatten.map {
                              case ValDef(pmods, pname, ptpt, prhs) =>
                                ValDef(
                                  pmods | Flag.PARAM,
                                  pname,
                                  AppliedTypeTree(
                                    typePath(contextName + ".Expr"),
                                    List(ptpt)),
                                  EmptyTree)
                            }
                          )
                      ) ++
                      (
                        if (tparams.isEmpty)
                          Nil
                        else
                          List(
                            tparams.map {
                              case tparam @ TypeDef(_, tname, _, _) =>
                                ValDef(
                                  Modifiers(Flag.IMPLICIT | Flag.PARAM),
                                  unit.fresh.newName("evidence$"),
                                  AppliedTypeTree(
                                    typePath(contextName + ".WeakTypeTag"),
                                    List(Ident(tname))),
                                  EmptyTree)
                            }
                          )
                      ),
                      AppliedTypeTree(
                        typePath(contextName + ".Expr"),
                        List(tpt)
                      ),
                      Block(
                        //newImportAll(termPath(contextName: T), tree.pos),
                        newImportAll(termPath(contextName + ".universe"), tree.pos),
                        ValDef(
                          NoMods,
                          selfTreeName,
                          newEmptyTpt(),
                          Match(
                            Annotated(
                              Apply(
                                Select(
                                  New(
                                    typePath("scala.unchecked")
                                  ),
                                  nme.CONSTRUCTOR
                                ),
                                Nil),
                              termPath(contextName + ".prefix.tree")),
                            List(
                              CaseDef(
                                Apply(
                                  Ident("Apply": TermName),
                                  List(
                                    Ident("_": TermName),
                                    Apply(
                                      Ident("List": TermName),
                                      List(
                                        Bind(
                                          selfTreeName,
                                          Ident("_": TermName)))))),
                                Ident(selfTreeName))
                            )
                          )
                        ),
                        ValDef(
                          NoMods,
                          selfName,
                          newEmptyTpt(),
                          Apply(
                            TypeApply(
                              termPath(contextName + ".Expr"),
                              List(targetTpt)),
                            List(
                              Ident(selfTreeName: TermName)))),
                        {
                          if ((flags & Flag.MACRO) != 0) {
                            // Extension body is already expressed as a macro, like `macro
                            rhs
                          } else {
                            val splicer = new Transformer {
                              override def transform(tree: Tree) = tree match {
                                case Ident(n) if n.isTermName && expressionNames.contains(n.toString) =>
                                  Select(tree, "splice": TermName)
                                case _ =>
                                  super.transform(tree)
                              }
                            }
                            Apply(
                              Ident("reify": TermName),
                              List(
                                splicer.transform(rhs)
                              )
                            )
                          }
                        }
                      )
                    )
                  )
                )
              )
            case _ =>
              List(super.transform(tree))
          }
        }
        def transformRuntimeExtension(tree: DefDef): Tree = {
          val DefDef(Modifiers(flags, privateWithin, annotations), name, tparams, vparamss, tpt, rhs) = tree
          val extendAnnotationOpt = annotations.find(ExtendAnnotation.unapply(_) != None)
          extendAnnotationOpt match
          {
            case Some(extendAnnotation @ ExtendAnnotation(targetValueTpt)) =>
              unit.warning(tree.pos, "This extension will create a runtime dependency. To use macro extensions, move this up to a publicly accessible module / object")
              val extensionName = newExtensionName(name)
              val targetTpt = typify(targetValueTpt)
              val typeNamesInTarget = getTypeNames(targetTpt).toSet
              val (outerTParams, innerTParams) =
                tparams.partition({ case tparam @ TypeDef(_, tname, _, _) => typeNamesInTarget.contains(tname) })
              val expressionNames = (vparamss.flatten.map(_.name.toString) :+ selfName.toString).toSet
              banVariableNames(expressionNames, rhs)
              ClassDef(
                Modifiers((flags | Flag.IMPLICIT) -- Flag.MACRO, privateWithin, Nil),
                extensionName: TypeName,
                outerTParams,
                Template(
                  List(parentTypeTreeForImplicitWrapper(targetTpt.toString: TypeName)),
                  newSelfValDef(),
                  genParamAccessorsAndConstructor(
                    List(selfName -> targetTpt)
                  ) :+
                  // Copying the original def over, without its annotation.
                  DefDef(Modifiers(flags -- Flag.MACRO, privateWithin, Nil), name, innerTParams, vparamss, tpt, rhs)
                )
              )
            case _ =>
              super.transform(tree)
          }
        }
        override def transform(tree: Tree): Tree = tree match {
          // TODO check that this module is statically and publicly accessible.
          case ModuleDef(mods, name, Template(parents, self, body)) if macroExtensions =>
            val newBody = body.flatMap {
              case dd @ DefDef(_, _, _, _, _, _) =>
                // Duplicate the resulting tree to avoid sharing any type tree between class and module..
                // TODO be finer than that and only duplicate type trees (or names?? not as straightforward as I thought).
                transformMacroExtension(dd).map(t => transform(t).duplicate)
              case member: Tree =>
                List(transform(member))
            }
            ModuleDef(mods, name, Template(parents, self, newBody))
          case dd @ DefDef(_, _, _, _, _, _) if runtimeExtensions =>
            transformRuntimeExtension(dd)
          case _ =>
            super.transform(tree)
        }
      }
      unit.body = onTransformer.transform(unit.body)
    }
  }
}