/**
 *  Copyright (C) 2002-2015   The FreeCol Team
 *
 *  This file is part of FreeCol.
 *
 *  FreeCol is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  FreeCol is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with FreeCol.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.sf.freecol.client.control;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.SwingUtilities;

import net.sf.freecol.client.ClientOptions;
import net.sf.freecol.client.FreeColClient;
import net.sf.freecol.common.debug.FreeColDebugger;
import net.sf.freecol.common.i18n.Messages;
import net.sf.freecol.common.model.Ability;
import net.sf.freecol.common.model.Colony;
import net.sf.freecol.common.model.DiplomaticTrade;
import net.sf.freecol.common.model.FoundingFather;
import net.sf.freecol.common.model.FreeColGameObject;
import net.sf.freecol.common.model.FreeColObject;
import net.sf.freecol.common.model.Game;
import net.sf.freecol.common.model.Goods;
import net.sf.freecol.common.model.HistoryEvent;
import net.sf.freecol.common.model.LastSale;
import net.sf.freecol.common.model.ModelMessage;
import net.sf.freecol.common.model.Modifier;
import net.sf.freecol.common.model.Ownable;
import net.sf.freecol.common.model.Player;
import net.sf.freecol.common.model.Stance;
import net.sf.freecol.common.model.Region;
import net.sf.freecol.common.model.Settlement;
import net.sf.freecol.common.model.Specification;
import net.sf.freecol.common.model.StringTemplate;
import net.sf.freecol.common.model.Tile;
import net.sf.freecol.common.model.TradeRoute;
import net.sf.freecol.common.model.Unit;
import net.sf.freecol.common.networking.ChatMessage;
import net.sf.freecol.common.networking.ChooseFoundingFatherMessage;
import net.sf.freecol.common.networking.Connection;
import net.sf.freecol.common.networking.DOMMessage;
import net.sf.freecol.common.networking.DiplomacyMessage;
import net.sf.freecol.common.networking.FirstContactMessage;
import net.sf.freecol.common.networking.IndianDemandMessage;
import net.sf.freecol.common.networking.LootCargoMessage;
import net.sf.freecol.common.networking.MonarchActionMessage;
import net.sf.freecol.common.networking.NewLandNameMessage;
import net.sf.freecol.common.networking.NewRegionNameMessage;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;


/**
 * Handles the network messages that arrives while in the game.
 *
 * Usually delegate to the real handlers in InGameController, making
 * sure anything non-trivial that touches the GUI is doing so inside
 * the EDT.  Usually this is done with SwingUtilities.invokeLater, but
 * some messages demand a response which requires invokeAndWait.
 *
 * Note that the EDT often calls the controller, which queries the
 * server, which results in handling the reply here, still within the
 * EDT.  invokeAndWait is illegal within the EDT, but none of messages
 * that require a response are client-initiated so the problem does
 * not arise...
 *
 * ...except for the special case of the animations.  These have to be
 * done in series but are sometimes in the EDT (our unit moves) and
 * sometimes not (other nation unit moves).  Hence the hack in the
 * local invokeAndWait wrapper.
 */
public final class InGameInputHandler extends InputHandler {

    private static final Logger logger = Logger.getLogger(InGameInputHandler.class.getName());

    // A bunch of predefined non-closure runnables.
    private final Runnable closeMenusRunnable = new Runnable() {
            @Override
            public void run() {
                getGUI().closeMenus();
            }
        };
    private final Runnable deselectActiveUnitRunnable = new Runnable() {
            @Override
            public void run() {
                getGUI().setActiveUnit(null);
            }
        };
    private final Runnable displayModelMessagesRunnable = new Runnable() {
            @Override
            public void run() {
                igc().displayModelMessages(false);
            }
        };
    private final Runnable reconnectRunnable = new Runnable() {
            @Override
            public void run() {
                igc().reconnect();
            }
        };
    private final Runnable updateMenuBarRunnable = new Runnable() {
            @Override
            public void run() {
                getGUI().updateMenuBar();
            }
        };


    /**
     * The constructor to use.
     *
     * @param freeColClient The <code>FreeColClient</code> for the game.
     */
    public InGameInputHandler(FreeColClient freeColClient) {
        super(freeColClient);
    }


    /**
     * Shorthand to get the controller.
     *
     * @return The in-game controller.
     */
    private InGameController igc() {
        return getFreeColClient().getInGameController();
    }

    /**
     * Wrapper for SwingUtilities.invokeAndWait.  This has to handle the
     * case where we are already in the EDT.
     *
     * @param runnable A <code>Runnable</code> to run.
     */
    private void invokeAndWait(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(runnable);
            } catch (Exception e) {}
        }
    }

    /**
     * Refresh the canvas.
     *
     * @param focus If true, request the focus.
     */
    private void refreshCanvas(final boolean focus) {
        SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    getGUI().refresh();

                    if (focus && !getGUI().isShowingSubPanel()) {
                        getGUI().requestFocusInWindow();
                    }
                }
            });
    }

    /**
     * Get the integer value of an element attribute.
     *
     * @param element The <code>Element</code> to query.
     * @param attrib The attribute to use.
     * @return The integer value of the attribute, or MIN_INT on failure.
     */
    private static int getIntegerAttribute(Element element, String attrib) {
        int n;
        try {
            n = Integer.parseInt(element.getAttribute(attrib));
        } catch (NumberFormatException e) {
            n = Integer.MIN_VALUE;
        }
        return n;
    }

    /**
     * Select a child element with the given object identifier from a
     * parent element.
     *
     * @param parent The parent <code>Element</code>.
     * @param key The key to search for.
     * @return An <code>Element</code> with matching key,
     *     or null if none found.
     */
    private static Element selectElement(Element parent, String key) {
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element)nodes.item(i);
            if (key.equals(FreeColObject.readId(e))) return e;
        }
        return null;
    }

    /**
     * Sometimes units appear which the client does not know about,
     * and are passed in as the children of the parent element.
     * Worse, if their location is a Unit, that unit has to be passed in too.
     *
     * @param game The <code>Game</code> to add the unit to.
     * @param element The <code>Element</code> to find a unit in.
     * @param id The object identifier of the unit to find.
     * @return A unit or null if none found.
     */
    private static Unit selectUnitFromElement(Game game, Element element,
                                              String id) {
        Element e = selectElement(element, id);
        Unit u = null;
        if (e != null) {
            u = new Unit(game, e);
            if (u.getLocation() == null) {
                throw new IllegalStateException("Null location: " + u);
            }
        }
        return u;
    }


    // Override InputHandler

    /**
     * {@inheritDoc}
     */
    @Override
    public Element handle(Connection connection, Element element) {
        if (element == null) {
            throw new RuntimeException("Received empty (null) message!");
        }

        Element reply;
        String type = element.getTagName();
        logger.log(Level.FINEST, "Received message: " + type);
        switch (type) {
        case "disconnect":
            reply = disconnect(element); break; // Inherited
        case "addObject":
            reply = addObject(element); break;
        case "addPlayer":
            reply = addPlayer(element); break;
        case "animateAttack":
            reply = animateAttack(element); break;
        case "animateMove":
            reply = animateMove(element); break;
        case "chat":
            reply = chat(element); break;
        case "chooseFoundingFather":
            reply = chooseFoundingFather(element); break;
        case "closeMenus":
            reply = closeMenus(); break;
        case "diplomacy":
            reply = diplomacy(element); break;
        case "error":
            reply = error(element); break;
        case "featureChange":
            reply = featureChange(element); break;
        case "firstContact":
            reply = firstContact(element); break;
        case "fountainOfYouth":
            reply = fountainOfYouth(element); break;
        case "gameEnded":
            reply = gameEnded(element); break;
        case "indianDemand":
            reply = indianDemand(element); break;
        case "lootCargo":
            reply = lootCargo(element); break;
        case "monarchAction":
            reply = monarchAction(element); break;
        case "multiple":
            reply = multiple(connection, element); break;
        case "newLandName":
            reply = newLandName(element); break;
        case "newRegionName":
            reply = newRegionName(element); break;
        case "newTurn":
            reply = newTurn(element); break;
        case "reconnect":
            reply = reconnect(element); break;
        case "remove":
            reply = remove(element); break;
        case "setAI":
            reply = setAI(element); break;
        case "setCurrentPlayer":
            reply = setCurrentPlayer(element); break;
        case "setDead":
            reply = setDead(element); break;
        case "setStance":
            reply = setStance(element); break;
        case "spyResult":
            reply = spyResult(element); break;
        case "update":
            reply = update(element); break;
        default:
            logger.warning("Unsupported message type: " + type);
            return null;
        }
        logger.log(Level.FINEST, "Handled message: " + type
            + " replying with: "
            + ((reply == null) ? "null" : reply.getTagName()));

        // If there is a "flush" attribute present, encourage the client
        // to display any new messages.
        final FreeColClient fcc = getFreeColClient();
        if (Boolean.TRUE.toString().equals(element.getAttribute("flush"))
            && fcc.currentPlayerIsMyPlayer()) {
            SwingUtilities.invokeLater(displayModelMessagesRunnable);
        }
        return reply;
    }


    // Individual message handlers

    /**
     * Add the objects which are the children of this Element.
     *
     * @param element The element (root element in a DOM-parsed XML
     *     tree) that holds all the information.
     * @return Null.
     */
    private Element addObject(Element element) {
        final Game game = getGame();
        final Specification spec = game.getSpecification();
        NodeList nodes = element.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element)nodes.item(i);
            String owner = e.getAttribute("owner");
            Player player = game.getFreeColGameObject(owner, Player.class);
            if (player == null) {
                logger.warning("addObject with broken owner: " + owner);
                continue;
            }

            final String tag = e.getTagName();
            if (FoundingFather.getXMLElementTagName().equals(tag)) {
                FoundingFather father = spec.getFoundingFather(FreeColObject.readId(e));
                if (father != null) player.addFather(father);
                player.invalidateCanSeeTiles();// Might be coronado?
                
            } else if (HistoryEvent.getXMLElementTagName().equals(tag)) {
                player.getHistory().add(new HistoryEvent(e));

            } else if (LastSale.getXMLElementTagName().equals(tag)) {
                player.addLastSale(new LastSale(e));

            } else if (ModelMessage.getXMLElementTagName().equals(tag)) {
                player.addModelMessage(new ModelMessage(e));

            } else if (TradeRoute.getXMLElementTagName().equals(tag)) {
                player.getTradeRoutes().add(new TradeRoute(game, e));

            } else {
                logger.warning("addObject unrecognized: " + tag);
            }
        }
        return null;
    }

    /**
     * Handles an "addPlayer"-message.
     *
     * @param element The element (root element in a DOM-parsed XML
     *     tree) that holds all the information.
     * @return Null.
     */
    private Element addPlayer(Element element) {
        final Game game = getGame();
        NodeList nodes = element.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element playerElement = (Element)nodes.item(i);
            String id = FreeColObject.readId(playerElement);
            Player p = game.getFreeColGameObject(id, Player.class);
            if (p == null) {
                game.addPlayer(new Player(game, playerElement));
            } else {
                p.readFromXMLElement(playerElement);
            }
        }
        return null;
    }

    /**
     * Handles an "animateAttack"-message.  This only performs animation, if
     * required.  It does not actually perform any attacks.
     *
     * @param element An element (root element in a DOM-parsed XML
     *     tree) that holds attributes for the old and new tiles and
     *     an element for the unit that is moving (which are used
     *     solely to operate the animation).
     * @return Null.
     */
    private Element animateAttack(Element element) {
        final FreeColClient freeColClient = getFreeColClient();
        final Game game = getGame();
        final Player player = freeColClient.getMyPlayer();
        String str;
        Unit u;

        if ((str = element.getAttribute("attacker")).isEmpty()) {
            throw new IllegalStateException("Attack animation for: "
                + player.getId() + " missing attacker attribute.");
        }
        if ((u = game.getFreeColGameObject(str, Unit.class)) == null
            && (u = selectUnitFromElement(game, element, str)) == null) {
            throw new IllegalStateException("Attack animation for: "
                + player.getId() + " omitted attacker: " + str);
        }
        // Note: we used to focus the map on the unit even when
        // animation is off as long as the center-active-unit option
        // was set.  However IR#115 requested that if animation is off
        // that we display nothing so as to speed up the other player
        // moves as much as possible.
        if (getGUI().getAnimationSpeed(u) <= 0) return null;

        if ((str = element.getAttribute("defender")).isEmpty()) {
            throw new IllegalStateException("Attack animation for: "
                + player.getId() + " missing defender attribute.");
        }
        if ((u = game.getFreeColGameObject(str, Unit.class)) == null
            && (u = selectUnitFromElement(game, element, str)) == null) {
            throw new IllegalStateException("Attack animation for: "
                + player.getId() + " omitted defender: " + str);
        }
        final Unit defender = u;

        if ((str = element.getAttribute("attackerTile")).isEmpty()) {
            throw new IllegalStateException("Attack animation for: "
                + player.getId() + " missing attacker tile attribute.");
        }
        final Tile attackerTile = game.getFreeColGameObject(str, Tile.class);
        if (attackerTile == null) {
            throw new IllegalStateException("Attack animation for: "
                + player.getId() + " omitted attacker tile: " + str);
        }

        if ((str = element.getAttribute("defenderTile")).isEmpty()) {
            throw new IllegalStateException("Attack animation for: "
                + player.getId() + " missing defender tile attribute.");
        }
        final Tile defenderTile = game.getFreeColGameObject(str, Tile.class);
        if (defenderTile == null) {
            throw new IllegalStateException("Attack animation for: "
                + player.getId() + " omitted defender tile: " + str);
        }

        final boolean success
            = Boolean.parseBoolean(element.getAttribute("success"));

        // All is well, do the animation.
        final Unit attacker = u;
        invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    getGUI().animateUnitAttack(attacker, defender,
                        attackerTile, defenderTile, success);
                    refreshCanvas(false);
                }
            });
        return null;
    }

    /**
     * Handles an "animateMove"-message.  This only performs
     * animation, if required.  It does not actually change unit
     * positions, which happens in an "update".
     *
     * @param element An element (root element in a DOM-parsed XML
     *     tree) that holds attributes for the old and new tiles and
     *     an element for the unit that is moving (which are used
     *     solely to operate the animation).
     * @return Null.
     */
    private Element animateMove(Element element) {
        final FreeColClient freeColClient = getFreeColClient();
        final Game game = getGame();
        final Player player = freeColClient.getMyPlayer();

        String unitId = element.getAttribute("unit");
        if (unitId.isEmpty()) {
            logger.warning("Animation for: " + player.getId()
                + " missing unitId.");
            return null;
        }
        Unit u = game.getFreeColGameObject(unitId, Unit.class);
        if (u == null) {
            u = selectUnitFromElement(game, element, unitId);
            //if (u != null) logger.info("Added unit from element: " + unitId);
        }
        if (u == null) {
            logger.warning("Animation for: " + player.getId()
                + " missing unit:" + unitId);
            return null;
        }
        // Note: we used to focus the map on the unit even when
        // animation is off as long as the center-active-unit option
        // was set.  However IR#115 requested that if animation is off
        // that we display nothing so as to speed up the other player
        // moves as much as possible.
        if (getGUI().getAnimationSpeed(u) <= 0) return null;

        String oldTileId = element.getAttribute("oldTile");
        if (oldTileId.isEmpty()) {
            logger.warning("Animation for: " + player.getId()
                + " missing oldTileId");
            return null;
        }
        final Tile oldTile = game.getFreeColGameObject(oldTileId, Tile.class);
        if (oldTile == null) {
            logger.warning("Animation for: " + player.getId()
                + " missing oldTile: " + oldTileId);
            return null;
        }

        String newTileId = element.getAttribute("newTile");
        if (newTileId.isEmpty()) {
            logger.warning("Animation for: " + player.getId()
                + " missing newTileId");
            return null;
        }
        final Tile newTile = game.getFreeColGameObject(newTileId, Tile.class);
        if (newTile == null) {
            logger.warning("Animation for: " + player.getId()
                + " missing newTile: " + newTileId);
            return null;
        }

        final Unit unit = u;
        invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    getGUI().animateUnitMove(unit, oldTile, newTile);
                    refreshCanvas(false);
                }
            });
        return null;
    }

    /**
     * Handles a "chat"-message.
     *
     * @param element The element (root element in a DOM-parsed XML
     *     tree) that holds all the information.
     * @return Null.
     */
    private Element chat(Element element) {
        final Game game = getGame();
        final ChatMessage chatMessage = new ChatMessage(game, element);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                getGUI().displayChatMessage(chatMessage.getPlayer(game),
                                            chatMessage.getMessage(),
                                            chatMessage.isPrivate());
            }
        });
        return null;
    }

    /**
     * Handles an "chooseFoundingFather"-request.
     *
     * @param element The element (root element in a DOM-parsed XML
     *     tree) that holds all the information.
     * @return Null.  The choice is returned asynchronously.
     */
    private Element chooseFoundingFather(Element element) {
        final ChooseFoundingFatherMessage message
            = new ChooseFoundingFatherMessage(getGame(), element);
        final List<FoundingFather> ffs = message.getFathers();

        getGUI().showChooseFoundingFatherDialog(ffs);
        return null;
    }

    /**
     * Trivial handler to allow the server to signal to the client
     * that an offer that caused a popup (for example, a native demand
     * or diplomacy proposal) has not been answered quickly enough and
     * that the offering player has assumed this player has
     * refused-by-inaction, and therefore, the popup needs to be
     * closed.
     *
     * @return Null.
     */
    private Element closeMenus() {
        invokeAndWait(closeMenusRunnable);
        return null;
    }

    /**
     * Handles a "diplomacy"-request.  If the message informs of an
     * acceptance or rejection then display the result and return
     * null.  If the message is a proposal, then ask the user about
     * it and return the response with appropriate response set.
     *
     * @param element The element (root element in a DOM-parsed XML
     *     tree) containing a "diplomacy"-message.
     * @return A diplomacy response, or null if none required.
     */
    private Element diplomacy(Element element) {
        final Game game = getGame();
        final DiplomacyMessage message
            = new DiplomacyMessage(getGame(), element);
        final DiplomaticTrade agreement = message.getAgreement();

        final FreeColGameObject our = message.getOurFCGO(game);
        if (our == null) {
            logger.warning("Our FCGO omitted from diplomacy message.");
            return null;
        }

        final FreeColGameObject other = message.getOtherFCGO(game);
        if (other == null) {
            logger.warning("Other FCGO omitted from diplomacy message.");
            return null;
        }

        invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    message.setAgreement(igc().diplomacy(our, other,
                                                         agreement));
                }
            });
        SwingUtilities.invokeLater(updateMenuBarRunnable);
        return (message.getAgreement() == null) ? null
            : message.toXMLElement();
    }

    /**
     * Disposes of the <code>Unit</code>s which are the children of this
     * Element.
     *
     * @param element The element (root element in a DOM-parsed XML
     *     tree) that holds all the information.
     * @return Null.
     */
    private Element disposeUnits(Element element) {
        Game game = getGame();
        NodeList nodes = element.getChildNodes();

        for (int i = 0; i < nodes.getLength(); i++) {
            // Do not read the whole unit out of the element as we are
            // only going to dispose of it, not forgetting that the
            // server may have already done so and its view will only
            // mislead us here in the client.
            Element e = (Element) nodes.item(i);
            String id = FreeColObject.readId(e);
            Unit u = game.getFreeColGameObject(id, Unit.class);
            if (u == null) {
                logger.warning("Object is not a unit");
            } else {
                u.dispose();
            }
        }
        return null;
    }

    /**
     * Handles an "error"-message.
     *
     * @param element The element (root element in a DOM-parsed XML
     *     tree) that holds all the information.
     * @return Null.
     */
    private Element error(Element element) {
        final String messageId = element.getAttribute("messageID");
        final String message = element.getAttribute("message");

        SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    getGUI().showErrorMessage(messageId, message);
                }
            });
        return null;
    }

    /**
     * Adds a feature to or removes a feature from a FreeColGameObject.
     *
     * @param element The element (root element in a DOM-parsed XML
     *     tree) that holds all the information.
     * @return Null.
     */
    private Element featureChange(Element element) {
        final Game game = getGame();
        final Specification spec = game.getSpecification();
        boolean add = "add".equalsIgnoreCase(element.getAttribute("add"));
        String id = FreeColObject.readId(element);
        FreeColGameObject object = game.getFreeColGameObject(id);
        if (object == null) {
            logger.warning("featureChange with null object");
            return null;
        }

        NodeList nodes = element.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);

            final String tag = e.getTagName();
            if (Ability.getXMLElementTagName().equals(tag)) {
                if (add) {
                    object.addAbility(new Ability(e, spec));
                } else {
                    object.removeAbility(new Ability(e, spec));
                }

            } else if (Modifier.getXMLElementTagName().equals(tag)) {
                if (add) {
                    object.addModifier(new Modifier(e, spec));
                } else {
                    object.removeModifier(new Modifier(e, spec));
                }

            } else {
                logger.warning("featureChange unrecognized: " + tag);
            }
        }
        return null;
    }

    /**
     * Handle a first contact with a native nation.
     *
     * @param element The element (root element in a DOM-parsed XML
     *     tree) that holds all the information.
     * @return Null.
     */
    private Element firstContact(Element element) {
        final Game game = getGame();
        final FirstContactMessage message
            = new FirstContactMessage(game, element);

        final Player player = message.getPlayer(game);
        if (player == null || player != getFreeColClient().getMyPlayer()) {
            logger.warning("firstContact with bad player: " + player);
            return null;
        }
        final Player other = message.getOtherPlayer(game);
        if (other == null || other == player || !other.isIndian()) {
            logger.warning("firstContact with bad other player: " + other);
            return null;
        }
        final Tile tile = message.getTile(game);
        if (tile != null && tile.getOwner() != other) {
            logger.warning("firstContact with bad tile: " + tile);
            return null;
        }

        getGUI().showFirstContactDialog(player, other, tile,
                                        message.getSettlementCount());
        return null;
    }

    /**
     * Ask the player to choose migrants from a fountain of youth event.
     *
     * @param element The element (root element in a DOM-parsed XML
     *     tree) that holds all the information.
     * @return Null.
     */
    private Element fountainOfYouth(Element element) {
        int n = getIntegerAttribute(element, "migrants");
        if (n > 0) {
            igc().fountainOfYouth(n);
        } else {
            logger.warning("Invalid migrants attribute: "
                + element.getAttribute("migrants"));
        }
        return null;
    }

    /**
     * Handles a "gameEnded"-message.
     *
     * @param element The element (root element in a DOM-parsed XML
     *     tree) that holds all the information.
     * @return Null.
     */
    private Element gameEnded(Element element) {
        FreeColClient freeColClient = getFreeColClient();
        FreeColDebugger.finishDebugRun(freeColClient, true);
        final Player winner
            = getGame().getFreeColGameObject(element.getAttribute("winner"),
                                             Player.class);
        final boolean highScore
            = "true".equalsIgnoreCase(element.getAttribute("highScore"));

        if (winner == freeColClient.getMyPlayer()) {
            SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        igc().displayHighScores(highScore);
                    }
                });
            getGUI().showVictoryDialog();
        }
        return null;
    }

    /**
     * Handles an "indianDemand"-request.
     *
     * @param element The element (root element in a DOM-parsed XML
     *     tree) that holds all the information.
     * @return An <code>IndianDemand</code> message containing the response,
     *     or null on error.
     */
    private Element indianDemand(Element element) {
        final Game game = getGame();
        final Player player = getFreeColClient().getMyPlayer();
        final IndianDemandMessage message
            = new IndianDemandMessage(game, element);

        final Unit unit = message.getUnit(game);
        if (unit == null) {
            logger.warning("IndianDemand with null unit: "
                + element.getAttribute("unit"));
            return null;
        }

        final Colony colony = message.getColony(game);
        if (colony == null) {
            logger.warning("IndianDemand with null colony: "
                + element.getAttribute("colony"));
            return null;
        } else if (!player.owns(colony)) {
            throw new IllegalArgumentException("Demand to anothers colony");
        }

        invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    boolean accepted = igc().indianDemand(unit, colony,
                        message.getType(game), message.getAmount());
                    message.setResult(accepted);
                }
            });
        return message.toXMLElement();
    }

    /**
     * Ask the player to choose something to loot.
     *
     * @param element The element (root element in a DOM-parsed XML
     *     tree) that holds all the information.
     * @return Null.  The choice is returned asynchronously.
     */
    private Element lootCargo(Element element) {
        final Game game = getGame();
        final LootCargoMessage message = new LootCargoMessage(game, element);
        final Unit unit = message.getUnit(game);
        final String defenderId = message.getDefenderId();
        final List<Goods> goods = message.getGoods();
        if (unit == null || goods == null) return null;

        getGUI().showCaptureGoodsDialog(unit, goods, defenderId);
        return null;
    }

    /**
     * Handles a "monarchAction"-request.
     *
     * @param element The element (root element in a DOM-parsed XML
     *     tree) that holds all the information.
     * @return Null.  The response is returned asynchronously.
     */
    private Element monarchAction(Element element) {
        final Game game = getGame();
        final MonarchActionMessage message
            = new MonarchActionMessage(game, element);

        getGUI().showMonarchDialog(message.getAction(), message.getTemplate(),
                                   message.getMonarchKey());
        return null;
    }

    /**
     * Handle all the children of this element.
     *
     * @param connection The <code>Connection</code> the element arrived on.
     * @param element The <code>Element</code> to process.
     * @return An <code>Element</code> containing the response/s.
     */
    private Element multiple(Connection connection, Element element) {
        NodeList nodes = element.getChildNodes();
        List<Element> results = new ArrayList<>();

        for (int i = 0; i < nodes.getLength(); i++) {
            try {
                Element reply = handle(connection, (Element)nodes.item(i));
                if (reply != null) results.add(reply);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Caught crash in multiple item " + i
                    + ", continuing.", e);
            }
        }
        return DOMMessage.collapseElements(results);
    }

    /**
     * Ask the player to name the new land.
     *
     * @param element The element (root element in a DOM-parsed XML
     *     tree) that holds all the information.
     * @return Null.  The name is returned asynchronously.
     */
    private Element newLandName(Element element) {
        final Game game = getGame();
        NewLandNameMessage message = new NewLandNameMessage(game, element);
        final Unit unit = message.getUnit(getFreeColClient().getMyPlayer());
        final String defaultName = message.getNewLandName();
        if (unit == null || defaultName == null 
            || !unit.hasTile()) return null;

        igc().newLandName(defaultName, unit);
        return null;
    }

    /**
     * Ask the player to name a new region.
     *
     * @param element The element (root element in a DOM-parsed XML
     *     tree) that holds all the information.
     * @return Null.  The name is returned asynchronously.
     */
    private Element newRegionName(Element element) {
        final Game game = getGame();
        NewRegionNameMessage message = new NewRegionNameMessage(game, element);
        final Tile tile = message.getTile(game);
        final Unit unit = message.getUnit(getFreeColClient().getMyPlayer());
        final Region region = message.getRegion(game);
        final String defaultName = message.getNewRegionName();
        if (defaultName == null || region == null) return null;

        igc().newRegionName(region, defaultName, tile, unit);
        return null;
    }

    /**
     * Handles a "newTurn"-message.
     *
     * @param element The element (root element in a DOM-parsed XML tree)
     *            that holds all the information.
     * @return Null.
     */
    private Element newTurn(Element element) {
        final int n = getIntegerAttribute(element, "turn");

        SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    igc().newTurn(n);
                }
            });

        igc().setCurrentPlayer(null);
        refreshCanvas(false);

        SwingUtilities.invokeLater(updateMenuBarRunnable);
        return null;
    }

    /**
     * Handles an "reconnect"-message.
     *
     * @param element The element (root element in a DOM-parsed XML
     *     tree) that holds all the information.
     * @return Null.
     */
    private Element reconnect(@SuppressWarnings("unused") Element element) {
        logger.finest("Entered reconnect.");

        SwingUtilities.invokeLater(reconnectRunnable);
        return null;
    }

    /**
     * Handles a "remove"-message.
     *
     * @param element The element (root element in a DOM-parsed XML
     *     tree) that holds all the information.
     * @return Null.
     */
    private Element remove(Element element) {
        final Game game = getGame();
        String ds = element.getAttribute("divert");
        FreeColGameObject divert = game.getFreeColGameObject(ds);
        Player player = getFreeColClient().getMyPlayer();
        boolean visibilityChange = false;

        NodeList nodeList = element.getChildNodes();
        for (int i = 0; i < nodeList.getLength(); i++) {
            Element e = (Element)nodeList.item(i);
            String idString = FreeColObject.readId(e);
            FreeColGameObject fcgo = game.getFreeColGameObject(idString);
            if (fcgo == null) {
                // This can happen legitimately when an update that
                // removes pointers to a disappearing unit happens,
                // then a gc which drops the weak reference in
                // freeColGameObjects, before this remove is processed.
                continue;
            }
            if (divert != null) {
                player.divertModelMessages(fcgo, divert);
            }
            if (fcgo instanceof Settlement) {
                Settlement settlement = (Settlement)fcgo;
                if (settlement != null && settlement.getOwner() != null) {
                    settlement.getOwner().removeSettlement(settlement);
                }
                visibilityChange = true;//-vis(player)
                
            } else if (fcgo instanceof Unit) {
                // Deselect the object if it is the current active unit.
                Unit u = (Unit)fcgo;
                if (u == getGUI().getActiveUnit()) {
                    invokeAndWait(deselectActiveUnitRunnable);
                }
                // Temporary hack until we have real containers.
                if (u != null && u.getOwner() != null) {
                    u.getOwner().removeUnit(u);
                }
                visibilityChange = true;//-vis(player)
            }

            // Do just the low level dispose that removes
            // reference to this object in the client.  The other
            // updates should have done the rest.
            fcgo.disposeResources();
        }
        if (visibilityChange) player.invalidateCanSeeTiles();//+vis(player)

        refreshCanvas(false);
        return null;
    }

    /**
     * Handles a "setAI"-message.
     *
     * @param element The element (root element in a DOM-parsed XML
     *     tree) that holds all the information.
     * @return Null.
     */
    private Element setAI(Element element) {
        final Game game = getGame();
        Player p = game.getFreeColGameObject(element.getAttribute("player"),
                                             Player.class);
        p.setAI(Boolean.parseBoolean(element.getAttribute("ai")));

        return null;
    }

    /**
     * Handles a "setCurrentPlayer"-message.
     *
     * @param element The element (root element in a DOM-parsed XML
     *     tree) that holds all the information.
     * @return Null.
     */
    private Element setCurrentPlayer(Element element) {
        Player player
            = getGame().getFreeColGameObject(element.getAttribute("player"),
                                             Player.class);
        
        igc().setCurrentPlayer(player);

        refreshCanvas(true);
        return null;
    }

    /**
     * Handles a "setDead"-message.
     *
     * @param element The element (root element in a DOM-parsed XML
     *     tree) that holds all the information.
     * @return Null.
     */
    private Element setDead(Element element) {
        final Player player = getGame()
            .getFreeColGameObject(element.getAttribute("player"),Player.class);

        SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    igc().setDead(player);
                }
            });
        return null;
    }

    /**
     * Handles a "setStance"-request.
     *
     * @param element The element (root element in a DOM-parsed XML
     *     tree) that holds all the information.
     * @return Null.
     */
    private Element setStance(Element element) {
        final Game game = getGame();
        final Stance stance = Enum.valueOf(Stance.class,
                                           element.getAttribute("stance"));
        final Player p1 = game
            .getFreeColGameObject(element.getAttribute("first"), Player.class);
        final Player p2 = game
            .getFreeColGameObject(element.getAttribute("second"),Player.class);

        SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    igc().setStance(stance, p1, p2);
                }
            });
        return null;
    }

    /**
     * Handles a "spyResult" message.
     *
     * @param element The element (root element in a DOM-parsed XML
     *     tree) that holds all the information.
     * @return Null.
     */
    private Element spyResult(Element element) {
        // The element contains two children, being the full and
        // normal versions of the settlement-being-spied-upon's tile.
        // It has to be the tile, as otherwise we do not see the units
        // defending the settlement.  So, we have to unpack, update
        // with the first, display, then update with the second.  This
        // is hacky as the client could retain the settlement
        // information, but the potential abuses are limited.
        NodeList nodeList = element.getChildNodes();
        if (nodeList.getLength() != 2) {
            logger.warning("spyResult length = " + nodeList.getLength());
            return null;
        }

        final Game game = getGame();
        final String tileId = element.getAttribute("tile");
        final Tile tile = game.getFreeColGameObject(tileId, Tile.class);
        if (tile == null) {
            logger.warning("spyResult bad tile = " + tileId);
            return null;
        }

        // Read the privileged tile information from fullElement, and
        // pass a runnable to the display routine that restores the
        // normal view of the tile, which happens when the colony panel
        // is closed.
        final Element fullElement = (Element)nodeList.item(0);
        final Element normalElement = (Element)nodeList.item(1);
        SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    tile.readFromXMLElement(fullElement);
                    getGUI().showSpyColonyPanel(tile, new Runnable() {
                        @Override
                            public void run() {
                                tile.readFromXMLElement(normalElement);
                            }
                        });
                }
            });
        return null;
    }

    /**
     * Handles an "update"-message.
     *
     * @param element The element (root element in a DOM-parsed XML
     *     tree) that holds all the information.
     * @return Null.
     */
    private Element update(Element element) {
        final Player player = getFreeColClient().getMyPlayer();
        boolean visibilityChange = false;

        NodeList nodeList = element.getChildNodes();
        for (int i = 0; i < nodeList.getLength(); i++) {
            Element e = (Element)nodeList.item(i);
            String id = FreeColObject.readId(e);
            FreeColGameObject fcgo = getGame().getFreeColGameObject(id);
            if (fcgo == null) {
                logger.warning("Update object not present in client: " + id);
            } else {
                fcgo.readFromXMLElement(e);
            }
            if ((fcgo instanceof Player && (fcgo == player))
                || ((fcgo instanceof Settlement || fcgo instanceof Unit)
                    && player.owns((Ownable)fcgo))) {
                visibilityChange = true;//-vis(player)
            }
        }
        if (visibilityChange) player.invalidateCanSeeTiles();//+vis(player)

        refreshCanvas(false);
        return null;
    }
}
